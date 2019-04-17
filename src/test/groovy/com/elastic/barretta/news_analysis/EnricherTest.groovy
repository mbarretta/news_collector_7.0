package com.elastic.barretta.news_analysis

import spock.lang.Specification

class EnricherTest extends Specification {
    def "AddEntities"() {
        given:
        def doc = [text: "mike is in VA"]
        def entities = [
            [
                type          : "PERSON",
                normalized    : "Michael Barretta",
                entityId      : "Q123",
                mentionOffsets: [
                    [
                        startOffset: 0,
                        endOffset  : 4
                    ]

                ]
            ],
            [
                type: "LOCATION",
                normalized: "Virginia",
                entityId: "Q456",
                mentionOffsets: [
                    [
                        startOffset: 11,
                        endOffset: 13
                    ]
                ]
            ]
        ]

        when:
        doc = new Enricher().addEntities(doc, entities)

        then:
        doc.annotatedText != null
    }
}
