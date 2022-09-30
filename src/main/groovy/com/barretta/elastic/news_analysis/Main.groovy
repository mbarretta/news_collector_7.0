package com.barretta.elastic.news_analysis

import groovy.cli.commons.CliBuilder
import groovy.time.TimeCategory
import groovy.util.logging.Slf4j

@Slf4j
class Main {
    static void main(String[] args) {
        def cli = new  CliBuilder(usage: 'Main <command>', header: "Commands:")
        cli.collect("collect news from APIs")
        cli.momentum("generate momentum metrics")
        cli.help("print this message")
        def options = cli.parse(args)
        if (args.length == 0 || !options || options.help) {
            cli.usage()
            System.exit(0)
        } else {
            def timeStart = new Date()
            if (options.collect) {
                NewsCollector.run()
            } else if (options.momentum) {
                EntityMomentum.run()
            }
            def timeStop = new Date()
            log.debug("TIMER: finished run in [${TimeCategory.minus(timeStop, timeStart)}]")
            System.exit(0)
        }
    }
}
