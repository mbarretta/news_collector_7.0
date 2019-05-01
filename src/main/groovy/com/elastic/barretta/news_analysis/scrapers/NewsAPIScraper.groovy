package com.elastic.barretta.news_analysis.scrapers

import com.elastic.barretta.clients.ESClient
import com.elastic.barretta.news_analysis.Enricher
import com.elastic.barretta.news_analysis.PropertyManager
import com.elastic.barretta.news_analysis.Utils
import de.l3s.boilerpipe.extractors.ArticleExtractor
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool

import java.util.concurrent.ConcurrentHashMap

@Slf4j
class NewsAPIScraper {

    final static String API_URL = "https://newsapi.org/v1/articles"

    static class Config {
        String key
        List sources = []

        @Override
        String toString() {
            return "Config{" +
                "key='" + key + '\'' +
                ", sources=" + sources +
                '}'
        }
    }

    static def scrape(Config config, ESClient client) {
        if (!config.key) {
            log.error("no API `key` set for NewsAPI - skipping")
            return [:]
        }
        if (!config.sources || config.sources.isEmpty()) {
            log.error("no `sources` defined for NewsAPI - skipping")
            return [:]
        }

        def enricher = new Enricher()
        def results = [:] as ConcurrentHashMap

        GParsPool.withPool(PropertyManager.instance.properties.maxThreads as int) {
            config.sources.eachParallel {
                log.info("fetching source [$it]")

                def posted = 0
                def url = new URL(API_URL + "?apiKey=${config.key}&source=$it")

                try {

                    //loop through each article we found...
                    new JsonSlurper().parse(url).articles.each { article ->
                        def doc = [
                            title : article.title,
                            url   : article.url,
                            byline: article.author,
                            date  : article.publishedAt,
                            source: it,
                            text  : ArticleExtractor.INSTANCE.getText(article.url.toURL())
                        ]

                        //if it has a body...
                        if (doc.text && !doc.text.trim().isEmpty()) {

                            //if it's new, write it
                            if (!client.existsByMatch("url.keyword", doc.url)) {
                                doc = enricher.enrich(doc)
                                def newId = client.index(doc)

                                Utils.writeEntitySentimentsToOwnIndex(newId, doc, client)
                                posted++
                            }

                            //else, decide if we should update it or ignore it
                            else {
                                log.trace("doc [$article.url] already exists in index")
                                def existingDoc = client.termQuery("url.keyword", doc.url).hits.hits[0].sourceAsMap

                                //if the doc has a new published date, we'll assume content was changed or added: we'll be doing an update
                                if (existingDoc._source.date != doc.date) {
                                    log.trace("...updating due to newer timestamp [${doc.date} vs [${existingDoc._source.date}]")
                                    doc = enricher.enrich(doc)
                                    doc._id = existingDoc._id
                                    client.update(doc)
                                    posted++
                                }
                            }
                        }
                    }
                    log.trace("...posted [$posted]")
                    results << [(it): posted]
                } catch (e) {
                    log.error("error fetching or posting article [$e.cause] [$e.message]")
                }
            }
        }
        log.info("results:\n$results")
        return results
    }

}