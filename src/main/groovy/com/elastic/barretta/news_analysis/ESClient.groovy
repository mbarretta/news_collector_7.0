package com.elastic.barretta.news_analysis

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.apache.http.HttpHost
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.client.CredentialsProvider
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.elasticsearch.action.bulk.BulkProcessor
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.bulk.BulkResponse
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder
import org.elasticsearch.client.RestHighLevelClient
import org.elasticsearch.common.unit.TimeValue
import wslite.http.auth.HTTPBasicAuthorization
import wslite.rest.RESTClient
import wslite.rest.RESTClientException
/**
 * lightweight ES client
 */
@Slf4j
class ESClient {

    @Delegate
    private RESTClient client
    private RestHighLevelClient client2
    Config config

    static class Config {
        String url
        String index
        String type = "_doc"
        String user
        String pass

        //lame validation
        def isValid() {
            def valid = [url, index, type].inject(true) { b, k ->
                b &= (k != null && !k.isEmpty()); b
            }
            if (user != null && !user.isEmpty()) {
                valid &= !pass.isEmpty()
            }
            valid &= (url != null && url.startsWith("http"))
            return valid
        }

        @Override
        String toString() {
            return "Config{" +
                "url='" + url + '\'' +
                ", index='" + index + '\'' +
                ", type='" + type + '\'' +
                ", user='" + user + '\'' +
                ", pass='<hidden>'" +
                '}'
        }
    }

    ESClient(Config config) {
        this.client = new RESTClient(config.url as String)
        this.client.defaultContentTypeHeader = "application/json"
        this.config = config
        if (config.user) {
            client.authorization = new HTTPBasicAuthorization(config.user, config.pass)
            log.info("using basic auth")
        }
        testClient()
    }

    def postDoc(Map content, index = config.index, type = config.type) {
        try {
            def response = client.post(path: "/$index/$type") {
                json content
            }
            return response.json._id
        } catch (RESTClientException e) {
            log.error("error posting doc [$e.message] // path [/$index/$type]")
            if (e.response.statusCode == 400) {
                log.error("detail [\n${JsonOutput.prettyPrint(new String(e.response.data))}\n]")
            }
        }
    }

    def updateDoc(id, Map content, index = config.index, type = config.type) {
        try {
            client.put(path: "/$index/$type/$id") {
                json content
            }
        } catch (RESTClientException e) {
            log.error("error updating doc [$e.message] // path [/$index/$type/$id] content [\n$content\n]")
            if (e.response.statusCode == 400) {
                log.error("detail [\n${JsonOutput.prettyPrint(new String(e.response.data))}\n]")
            }
        }
    }

    def docExists(String field, String value, String index = config.index, String type = config.type) {
        def returnVal = true
        try {
            if (value) {
                def response = client.post(path: "/$index/$type/_search") {
                    json size: 0, query: [match: [(field): value]]
                }
                returnVal = response.json.hits.total.value > 0
            }
        } catch (RESTClientException e) {
            log.error("error determining doc existence [$e.cause] // path [/$index/$type/_search] field [$field] value [$value]")
            if (e.response.statusCode == 400) {
                log.error("detail [\n${JsonOutput.prettyPrint(new String(e.response.data))}\n]")
            }
        }
        return returnVal
    }

    def getDocByUniqueField(String uniqueField, String value, String index = config.index, String type = config.type) {
        def returnObj = [:]
        try {
            def response = client.post(path: "/$index/$type/_search") {
                json size: 1, query: [match: [(uniqueField): value]]
            }

            returnObj = response.json.hits.hits[0]
        } catch (RESTClientException e) {
            log.error("error fetching doc by unique field [$e.cause] // path [/$index/$type/_search] field [$uniqueField] value [$value]")
            if (e.response.statusCode == 400) {
                log.error("detail [\n${JsonOutput.prettyPrint(new String(e.response.data))}\n]")
            }
        }
        return returnObj
    }

    def scrollQuery(Map body, int batchSize = 100, String keepAlive = "1m", Closure mapFunction) {
        try {
            def response = client.post(path: "/$config.index/$config.type/_search?scroll=$keepAlive") {
                json size: batchSize, query: body
            }

            log.info("found [${response.json.hits.total}] records in [$config.index]")

            if (response.json.hits.total > 0) {
                def scrollId = response.json._scroll_id

                //do first batch
                log.debug("...processing first batch of [$batchSize]")
                response.json.hits.hits.collect().each {
                    mapFunction(it as Map)
                }

                //do other batches if we need to
                while (response.json.hits.hits.size() >= batchSize) {
                    response = client.post(path: "/_search/scroll") {
                        json scroll: keepAlive, scroll_id: scrollId
                    }
                    log.debug("...processing batch w/ [${response.json.hits.hits.size()}] results")
                    response.json.hits.hits.collect().each {
                        mapFunction(it as Map)
                    }
                    scrollId = response.json._scroll_id

                }
            }
        } catch (RESTClientException e) {
            log.error("error running scroll query [$e.cause] ")
            if (e.response.statusCode == 400) {
                log.error("detail [\n${JsonOutput.prettyPrint(new String(e.response.data))}\n]")
            }
        }
    }

    def bulkInsert(List<Map> records, String index = config.index, String ttype = config.type) {
        log.info("looking to bulk insert [${records.size()}] records")
        init()
        def recordCount = records.size()

        def listener = new BulkProcessor.Listener() {

            @Override
            void beforeBulk(long executionId, BulkRequest request) {
                log.info("bulk inserting [${request.numberOfActions()}] records to [$index]")
            }

            @Override
            void afterBulk(long executionId, BulkRequest request, BulkResponse response) {
                recordCount = request.numberOfActions()
            }

            @Override
            void afterBulk(long executionId, BulkRequest request, Throwable failure) {
                log.error("error running bulk insert [$failure.message]", failure)
                recordCount = 0

            }
        }
        def builder = BulkProcessor.builder(client2.&bulkAsync, listener).setFlushInterval(TimeValue.timeValueSeconds(5L)).build()

        records.each { record ->
            builder.add(new IndexRequest(index, ttype).source(record))
        }
        return recordCount
    }

    private testClient() {
        try {
            client.httpClient.connectTimeout = 5000
            client.get()
            log.info("able to connect to ES [$config.url]")
        } catch (RESTClientException e) {
            log.error("unable to connect to ES [${e.message}]\n$config")
            log.error(e.request.url.toString() + ":" + e.request.headers)
            System.exit(1)
        }
    }

    private init() {
        def url = new URL(config.url)
        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider()
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(config.user, config.pass))

        def builder = RestClient.builder(new HttpHost(url.host, url.port, url.protocol))
            .setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
            @Override
            public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
            }
        })
        client2 = new RestHighLevelClient(builder)
    }
}