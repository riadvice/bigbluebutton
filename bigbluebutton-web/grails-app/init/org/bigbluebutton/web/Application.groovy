package org.bigbluebutton.web

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
import org.bigbluebutton.common2.errortracking.ErrorTracking

import java.util.function.Function

class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        ErrorTracking.init("bbb-web", sentrySettings())
        GrailsApp.run(Application, args)
    }

    /** sentry.* from bigbluebutton.properties and /etc/bigbluebutton/bbb-web.properties, read before Grails starts. */
    static Function<String, String> sentrySettings() {
        Properties properties = new Properties()
        Application.getResourceAsStream("/bigbluebutton.properties")?.withReader("UTF-8") { properties.load(it) }
        File override = new File("/etc/bigbluebutton/bbb-web.properties")
        if (override.canRead()) {
            override.withReader("UTF-8") { properties.load(it) }
        }
        return { String key -> properties.getProperty("sentry." + key) } as Function<String, String>
    }
}
