package com.elastic.barretta.news_analysis

import com.elastic.barretta.clients.ESClient
import groovy.util.logging.Slf4j

import java.time.LocalDate

@Slf4j
class EntityMomentum {

    static void main(String[] args) {
        run()
        System.exit(0)
    }

    static def run(date = LocalDate.now().format("yyyy-MM-dd")) {
        def config = PropertyManager.instance.properties
        def esClient = new ESClient(config.es as ESClient.Config)
        esClient.config.index = config.indices.momentum

        log.info("scoring momentum for [$date]")

        //prevent duplicates by looking for data from "today"
        if (!esClient.existsByMatch("date", date)) {

            def results = Enricher.calculateMomentum(esClient, LocalDate.parse(date, "yyyy-MM-dd"))
            if (!results.isEmpty()) {
                def postList = results.inject([]) { l, k, v ->
                    l << [name: k, score: v, date: date]
                }
                esClient.bulk([(ESClient.BulkOps.INSERT):postList])
            } else {
                log.info("no records found, so no momentum records generated")
            }
        } else {
            log.info("documents with date found - skipping")
        }
    }
}
