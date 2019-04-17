package com.elastic.barretta.news_analysis

import com.elastic.barretta.news_analysis.scrapers.APScraper
import com.elastic.barretta.news_analysis.scrapers.NewsAPIScraper
import groovy.util.logging.Slf4j

/**
 * Main class
 */
@Slf4j
class NewsCollector {

    final static def DEFAULT_ES_URL = "http://localhost:9200"
    final static def DEFAULT_ES_INDEX_PREFIX = "news"
    final static def ENTITY_ES_INDEX = "entity_sentiment"
    final static def MOMENTUM_ES_INDEX = "entity_momentum"

    static class Config {
        ESClient.Config es = new ESClient.Config()

        NewsAPIScraper.Config newsApi = new NewsAPIScraper.Config()
        boolean clean = false
        String indexPrefix = DEFAULT_ES_INDEX_PREFIX
        String news_index
        String momentum_index
        String sentiment_index

        def setIndexPrefix(p) {
            indexPrefix = p
            news_index = indexPrefix
            momentum_index = indexPrefix + "_" + MOMENTUM_ES_INDEX
            sentiment_index = indexPrefix + "_" + ENTITY_ES_INDEX
        }

        def isValid() {
            def valid = true
            valid &= es.isValid()
            valid &= (indexPrefix != null && !indexPrefix.isEmpty())
            return valid
        }

        @Override
        String toString() {
            return "Config{" +
                "es=" + es +
                ", newsApi=" + newsApi +
                ", clean=" + clean +
                ", indexPrefix=" + indexPrefix +
                '}'
        }
    }

    static void main(String[] args) {
        run(doConfig())
    }

    /**
     * do it
     * @param config config map
     * @return map with some result info
     */
    static def run(Config config) {
        log.debug("running with config: [$config]")
        def client = new ESClient(config.es)

        config.es.index = config.news_index
        def results = [:]
        if (config.newsApi.key) {
            results += NewsAPIScraper.scrape(config.newsApi, client)
        }
        results += APScraper.scrape(client)

        return results
    }

    static def doConfig() {
        def appConfig = new Config()
        def esConfig = new ESClient.Config()
        def newsApiConfig = new NewsAPIScraper.Config()
        def propertyConfig = new ConfigSlurper().parse(this.classLoader.getResource("properties.groovy"))

        appConfig.newsApi = newsApiConfig
        appConfig.indexPrefix = propertyConfig.indexPrefix ?: DEFAULT_ES_INDEX_PREFIX

        esConfig.with {
            url = propertyConfig.es.url ?: DEFAULT_ES_URL
            user = propertyConfig.es.user ?: null
            pass = propertyConfig.es.pass ?: null
            index = appConfig.news_index //doing this mainly so we an pass validation
        }
        newsApiConfig.with {
            key = propertyConfig.newsApi.key
            sources = propertyConfig.newsApi.sources ?: []
        }
        appConfig.es = esConfig

        assert appConfig.isValid(): "config is hosed...fix it"

        return appConfig
    }
}