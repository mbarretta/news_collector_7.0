package com.elastic.barretta.news_analysis

import com.elastic.barretta.analytics.rosette.RosetteApiClient
import com.elastic.barretta.clients.ESClient
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsPool

import java.util.concurrent.ConcurrentLinkedQueue

@Slf4j
class Enricher {
    RosetteApiClient rosette

    Enricher() {
        def config = PropertyManager.instance.properties
        if (config.enrichment) {
            if (config.enrichment.rosetteApi) {
                try {
                    rosette = new RosetteApiClient(config.enrichment.rosetteApi as RosetteApiClient.Config)
                } catch (e) {
                    rosette = null
                    log.warn("unable to establish connection to Rosette API [${config.enrichment.rosetteApi}]")
                }
            } else {
                log.info("Rosette API config is not present - skipping init")
            }
        } else {
            log.warn("missing enrichment{} configuration - skipping enrichment")
        }
    }

    def enrich(Map doc) {
        if (rosette) {
            def entities = rosette.getEntities(doc.text)
            doc = addEntities(doc, entities)
            doc = addSentiment(doc)
        }
        return doc
    }

    def addEntities(Map doc, List entities = null) {
        if (rosette) {
            if (!entities) {
                entities = rosette.getEntities(doc.text)
            }

            def entityMentions = [] as ConcurrentLinkedQueue

            doc.entityNames = [] as ConcurrentLinkedQueue
            doc.entityPeople = [] as ConcurrentLinkedQueue
            doc.entityResolvedPeople = [] as ConcurrentLinkedQueue
            doc.entityOrgs = [] as ConcurrentLinkedQueue
            doc.entityResolvedOrgs = [] as ConcurrentLinkedQueue
            doc.entityObjects = [] as ConcurrentLinkedQueue
            doc.locations = [] as ConcurrentLinkedQueue
            doc.entityLocations = [] as ConcurrentLinkedQueue
            doc.entityResolvedLocations = [] as ConcurrentLinkedQueue
            doc.locationObjects = [] as ConcurrentLinkedQueue

            GParsPool.withPool(PropertyManager.instance.properties.maxThreads as int) {
                entities.eachParallel {
                    doc.entityNames << it.normalized
                    doc.entityObjects << [id: it.entityId, name: it.normalized, type: it.type, count: it.count]

                    def isResolved = it.entityId.startsWith("Q")
                    if (isResolved) {
                        it.mentionOffsets.each { mention ->
                            entityMentions << [start: mention.startOffset, end: mention.endOffset, annotation: [it.entityId, it.normalized, it.type]]
                        }
                    }

                    if (it.type == "PERSON") {
                        doc.entityPeople << it.normalized
                        if (isResolved) {
                            doc.entityResolvedPeople << it.normalized
                        }
                    } else if (it.type == "ORGANIZATION") {
                        doc.entityOrgs << it.normalized
                        if (isResolved) {
                            doc.entityResolvedOrgs << it.normalized
                        }
                    } else if (it.type == "LOCATION") {
                        doc = handleLocations(doc, it as Map)
                    }
                }
            }

            if (!entityMentions.isEmpty()) {
                doc.annotatedText = Utils.annotateText(doc.text, entityMentions.sort { it.start })
            }
        }
        return doc
    }

    static def handleLocations(Map doc, Map entity) {
        doc.entityLocations << entity.normalized
        def location = new JsonSlurper().parse("https://www.wikidata.org/w/api.php?format=json&action=wbgetentities&ids=${entity.entityId}".toURL())
        if (location) {
            def geo = location?.entities?."${entity.entityId}"?.claims?.find {
                it.value[0].mainsnak.datatype == "globe-coordinate" && it.value[0].mainsnak.datavalue.value.latitude != null
            }
            if (geo) {
                geo = geo.value[0].mainsnak.datavalue.value
                doc.locations << [lat: geo.latitude, lon: geo.longitude]
                doc.entityResolvedLocations << entity.normalized
                doc.locationObjects << [name: entity.normalized, geo: [lat: geo.latitude, lon: geo.longitude]]
            }
        }

        return doc
    }

    def addSentiment(Map doc) {
        if (rosette) {
            def sentimentMap = rosette.getSentiment(doc.text)
            if (sentimentMap) {
                doc.sentimentLabel = sentimentMap.document.label
                doc.sentimentConfidence = sentimentMap.document.confidence

                if (doc.entityObjects) {
                    doc.entityObjects.collect { target ->
                        def source = sentimentMap.entities.find { target.id == it.entityId }
                        target.sentimentLabel = source?.sentiment?.label
                        target.sentimentConfidence = source?.sentiment?.confidence
                    }
                }
            }
        }
        return doc
    }

    static def calculateMomentum(ESClient client, Date date = new Date()) {
        def dateString = date.format("yyyy-MM-dd")

        def body = [
            query: [
                range: [
                    date: [
                        gte: "$dateString 11:11:11||-3d/d",
                        lte: "$dateString 11:11:11||-1d/d"
                    ]
                ]
            ],
            aggs : [
                daily: [
                    date_histogram: [
                        field   : "date",
                        interval: "day"
                    ],
                    aggs          : [
                        entities: [
                            terms: [
                                field: "entityPeople.keyword",
                                size : 10000

                            ],
                            aggs : [
                                sources: [
                                    terms: [
                                        field        : "source",
                                        size         : 50,
                                        min_doc_count: 1
                                    ]
                                ]
                            ]
                        ]
                    ]
                ]
            ]
        ]
        def response = client.rawRequest("GET", "/${PropertyManager.instance.properties.indices.news}/_search", body)

        def data = [:].withDefault { 0 as double }
        def buckets = response.aggregations.daily.buckets

        // trying to do log(avg())*[1]/[0] + [2]/[1]) * min( (1/5) * numSources, 2)
        // intuition is to look at the "average" change in the mentions for this entity over the past three days,
        // adjust it on a log scale to boost score of those with a lot of mentions, and then increase that score to
        // reward those (up to 2x) who show up in more than one source - reward maxes out after 10 sources
        buckets.eachWithIndex { bucket, i ->

            //look at all three buckets and do the things
            bucket.entities.buckets.each { entity ->

                if (i < 2) {
                    def match = buckets[i + 1].entities.buckets.find { it.key == entity.key }
                    def diff = (match) ? match.doc_count / entity.doc_count : entity.doc_count
                    data[entity.key] += diff
                }

                // can't reach ahead to the next bucket anymore...
                else {

                    // if we're in the 3rd bucket and haven't seen this guy yet, throw him in
                    // otherwise he's already been considered during last loop
                    if (!data.containsKey(entity.key)) {
                        data[entity.key] += entity.doc_count
                    }

                    //finish our scoring considering data from all three buckets
                    def parent = buckets.entities.buckets.flatten().findAll { it.key == entity.key }
                    def sourceWeight = Math.min(parent.sources.buckets.flatten().size() / 5, 2 as double)
                    def mentionWeight = Math.log(parent.collect { it.doc_count }.sum() / 3)
                    data[entity.key] = sourceWeight * mentionWeight * data[entity.key] as double
                }
            }
        }
        return data
    }
}