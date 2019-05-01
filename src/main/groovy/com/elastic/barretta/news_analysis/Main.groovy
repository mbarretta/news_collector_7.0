package com.elastic.barretta.news_analysis

import groovy.cli.commons.CliBuilder

class Main {
    static void main(String[] args) {
        def cli = new  CliBuilder(usage: 'Main <command>', header: "Commands:")
        cli.collect("collect news from APIs")
        cli.momentum("generate momentum metrics")
        cli.help("print this message")
        def options = cli.parse(args)
        if (!options || options.help) {
            cli.usage()
            System.exit(0)
        } else {
            if (options.collect) {
                NewsCollector.run()
            } else if (options.momentum) {
                EntityMomentum.run()
            }
        }
    }
}
