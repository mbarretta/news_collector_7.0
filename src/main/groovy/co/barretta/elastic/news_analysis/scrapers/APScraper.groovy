package co.barretta.elastic.news_analysis.scrapers


import com.barretta.elastic.clients.ESClient
import co.barretta.elastic.news_analysis.Utils
import groovy.json.JsonSlurper
import groovy.time.TimeCategory
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool

import java.util.concurrent.ConcurrentHashMap

/**
 * Pull docs from AP and push into ES using supplied client
 */
@Slf4j
class APScraper {

    static def scrape(ESClient client) {

        def results = [:] as ConcurrentHashMap
        final def AP_URL = "https://afs-prod.appspot.com/api/v2/feed/tag?tags="

        def enricher = new co.barretta.elastic.news_analysis.Enricher()

        //fetch most recent article "cards" from each section
        [
            "apf-topnews",
            "apf-usnews",
            "apf-intlnews",
            "apf-business",
            "apf-politics",
            "apf-technology",
            "apf-Health",
            "apf-science"
        ].each { tag ->
            log.info("fetching for [$tag]")

            try {
                //get all the urls from each card
                def articleUrls = new JsonSlurper().parse((AP_URL + tag).toURL()).cards.contents*.gcsUrl.flatten()
                log.trace("...found [${articleUrls.size()}] articles")

                //fetch article for each url found
                def posted = 0
                GParsPool.withPool(co.barretta.elastic.news_analysis.PropertyManager.instance.properties.maxThreads as int) {
                    articleUrls.collectParallel { new JsonSlurper().parse(it.toURL()) }.eachParallel { article ->
                        log.trace("starting article [${article.shortId}]")
                        def timeStart = new Date()

                        //quality-check - not all "articles" are actually articles
                        if (article && article.storyHTML && !article.shortId.contains(":")) {
                            def doc = [
                                title  : article.title,
                                shortId: article.shortId,
                                url    : article.localLinkUrl,
                                byline : article.bylines,
                                date   : article.published,
                                source : "associated-press",
                                section: tag,
                                text   : article.storyHTML.replaceAll(/<.*?>/, "").replaceAll(/\s{2,}/, " ")
                            ]

                            //filter empties and duplicates
                            if (doc.text && !doc.text.trim().isEmpty() &&
                                !client.existsByMatch("url.keyword", article.localLinkUrl) && !client.existsByMatch("shortId", article.shortId)) {
                                def newId = client.index(enricher.enrich(doc))
                                Utils.writeEntitySentimentsToOwnIndex(newId, doc, client)
                                posted++
                            } else {
                                log.trace("doc [$article.shortId] already exists in index or has no body text - skipping")
                            }
                        } else {
                            log.trace("empty article found [$article]")
                        }

                        def timeStop = new Date()
                        log.trace("finished article [${article.shortId}]")
                        log.debug("TIMER: processed article [${article.shortId} in [${TimeCategory.minus(timeStop, timeStart)}]")
                    }
                    log.trace("...posted [$posted]")
                    results << [(tag): posted]
                }
            } catch (e) {
                log.error("error", e)
            }
        }

        log.info("results:\n$results")
        return results
    }
}