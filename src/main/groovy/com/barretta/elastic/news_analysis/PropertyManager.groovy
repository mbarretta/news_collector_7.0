package com.barretta.elastic.news_analysis

@Singleton(strict = false)
class PropertyManager {
    def properties = [:]

    private PropertyManager() {
        properties = new ConfigSlurper().parse(GroovyClassLoader.getSystemResource("properties.groovy"))
    }
}
