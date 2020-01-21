package com.elastic.barretta.news_analysis

import com.elastic.barretta.clients.ESClient
import com.elastic.barretta.news_analysis.scrapers.APScraper
import com.elastic.barretta.news_analysis.scrapers.NewsAPIScraper
import groovy.util.logging.Slf4j

/**
 * Main class
 */
@Slf4j
class NewsCollector {

    static void main(String[] args) {
        run()
    }

    static def run() {
        def config = PropertyManager.instance.properties

        log.debug("running with config: [$config]")
        def client = new ESClient(config.es as ESClient.Config)
        assert client.test()
        client.config.index = config.indices.news
        def results = [:]
        if (config.newsApi.key) {
            results += NewsAPIScraper.scrape(config.newsApi as NewsAPIScraper.Config, client)
        }
        results += APScraper.scrape(client)

        return results
    }


}