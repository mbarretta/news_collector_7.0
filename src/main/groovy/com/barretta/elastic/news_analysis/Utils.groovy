package com.barretta.elastic.news_analysis

import com.barretta.elastic.clients.ESClient
import groovy.util.logging.Slf4j

@Slf4j
class Utils {
    static def writeEntitySentimentsToOwnIndex(String id, Map doc, ESClient client) {
        def date = doc.date
        doc.entityObjects?.each {
            if (["PERSON", "ORGANIZATION", "LOCATION"].contains(it.type) && it.sentimentLabel) {
                client.index(
                    [
                        date      : date,
                        name      : it.name,
                        type      : it.type,
                        sentiment : it.sentimentLabel,
                        confidence: it.sentimentConfidence,
                        value     : it.sentimentLabel == "pos" ? 1 : it.sentimentLabel == "neg" ? -1 : 0,
                        articleId : id,
                        source    : it.source
                    ],
                    PropertyManager.instance.properties.indices.sentiment
                )
            }
        }
    }

    static def annotateText(String text, List<Map> mentions) {
        def annotatedText = new StringBuilder()
        def last = 0
        mentions.each { mention ->
            if (mention.start > 0) {
                annotatedText.append(text[last..mention.start - 1])
            }
            annotatedText.append("[")
            annotatedText.append(text[mention.start..mention.end - 1])
            annotatedText.append("]").append("(").append(mention.annotation.collect { URLEncoder.encode(it, "UTF-8") }.join("&")).append(")")
            last = mention.end
        }
        if (last != text.length()) {
            annotatedText.append(text[last..-1])
        }
        return annotatedText.toString()
    }

    def dealWithDates(String date) {

    }
}
