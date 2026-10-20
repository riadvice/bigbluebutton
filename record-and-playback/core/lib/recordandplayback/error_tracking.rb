# frozen_string_literal: true

# Copyright © 2026 BigBlueButton Inc. and by respective authors.
#
# This file is part of BigBlueButton open source conferencing system.
#
# BigBlueButton is free software: you can redistribute it and/or modify it
# under the terms of the GNU Lesser General Public License as published by the
# Free Software Foundation, either version 3 of the License, or (at your
# option) any later version.
#
# BigBlueButton is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
# FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more
# details.
#
# You should have received a copy of the GNU Lesser General Public License
# along with BigBlueButton.  If not, see <http://www.gnu.org/licenses/>.

require 'English'
require 'logger'
require 'uri'

module BigBlueButton
  # Optional error reporting for the recording scripts, started by recordandplayback.rb.
  # Settings: BBB_SENTRY_* from the environment, else the +sentry+ map of recording.yml.
  module ErrorTracking
    RELEASE_FILE = '/etc/bigbluebutton/bigbluebutton-release'

    SENTRY_LEVELS = {
      ::Logger::DEBUG => :debug,
      ::Logger::INFO => :info,
      ::Logger::WARN => :warning,
      ::Logger::ERROR => :error,
      ::Logger::FATAL => :fatal,
      ::Logger::UNKNOWN => :fatal,
    }.freeze

    LOG_LEVELS = {
      ::Logger::DEBUG => :debug,
      ::Logger::INFO => :info,
      ::Logger::WARN => :warn,
      ::Logger::ERROR => :error,
      ::Logger::FATAL => :fatal,
      ::Logger::UNKNOWN => :fatal,
    }.freeze

    # Set while a record is reported, so the SDK's own logging cannot loop.
    REPORTING = :bbb_error_tracking_reporting

    # Filters secrets and masks personal data, by the name of each pair.
    class Anonymiser
      FILTERED = '[Filtered]'

      SECRET_NAME = /pass|secret|token|checksum|salt|api[_-]?key|authori[sz]ation|cookie|pw$/i
      PERSONAL_NAME = /name$|e-?mail|address$|^ip$|^message$|^text$|^msg$/i

      # The bounded name keeps the scan linear. A value stops at '?' and '=' so a
      # URL value (url=https://h/p?token=x) leaves its own query pairs to match.
      QUERY_PAIR = /([\w.\-\[\]]{1,256})=([^&\s"'<>,;?=]*)/
      JSON_PAIR = /"([\w.\-]{1,256})"(\s*:\s*)"((?:[^"\\]|\\.)*)"/
      IPV4 = /(?<![\d.])(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?![\d.])/

      def self.mode(value)
        value.to_s.strip.downcase == 'off' ? :off : :mask
      end

      attr_reader :mode

      def initialize(mode)
        @mode = mode
      end

      def anonymise_text(text)
        return text if text.nil? || text.empty?

        result = text.gsub(QUERY_PAIR) do
          name = Regexp.last_match(1)
          filtered?(name) ? "#{name}=#{FILTERED}" : Regexp.last_match(0)
        end
        result = result.gsub(JSON_PAIR) do
          name = Regexp.last_match(1)
          separator = Regexp.last_match(2)
          filtered?(name) ? "\"#{name}\"#{separator}\"#{FILTERED}\"" : Regexp.last_match(0)
        end
        result = result.gsub(IPV4, FILTERED) unless @mode == :off
        result
      end

      def anonymise_value(name, value)
        return FILTERED if !name.nil? && !value.nil? && filtered?(name.to_s)

        case value
        when String then anonymise_text(value)
        when Hash then value.to_h { |key, item| [key, anonymise_value(key, item)] }
        when Array then value.map { |item| anonymise_value(nil, item) }
        else value
        end
      end

      def anonymise_event(event)
        event.message = anonymise_text(event.message) if event.message.is_a?(String)
        event.exception&.values&.each do |exception|
          exception.value = anonymise_text(exception.value) if exception.value.is_a?(String)
        end
        event.breadcrumbs&.each { |breadcrumb| anonymise_breadcrumb(breadcrumb) }
        event.extra = anonymise_value(nil, event.extra) if event.extra.is_a?(Hash)
        request = event.request
        if request
          request.url = anonymise_text(request.url) if request.url.is_a?(String)
          request.query_string = anonymise_text(request.query_string) if request.query_string.is_a?(String)
          request.headers = anonymise_value(nil, request.headers) if request.headers.is_a?(Hash)
        end
        event
      end

      def anonymise_breadcrumb(breadcrumb)
        breadcrumb.message = anonymise_text(breadcrumb.message) if breadcrumb.message.is_a?(String)
        breadcrumb.data = anonymise_value(nil, breadcrumb.data) if breadcrumb.data.is_a?(Hash)
        breadcrumb
      end

      # The raw arguments of a log call are dropped; the body is enough.
      def anonymise_log(log)
        log.body = anonymise_text(log.body) if log.body.is_a?(String)
        # Without the template (the body before anonymising) the SDK sends the body as is.
        log.template = nil
        log.attributes = log.attributes.each_with_object({}) do |(name, value), attributes|
          next if name.to_s.start_with?('sentry.message.parameter') || name.to_s == 'parameters'

          attributes[name] = value.is_a?(String) ? anonymise_text(value) : value
        end
        log
      end

      private

      def filtered?(name)
        SECRET_NAME.match?(name) || (@mode != :off && PERSONAL_NAME.match?(name))
      end
    end

    # The level methods of ::Logger and Journald::Logger all go through #add.
    module LoggerHook
      def add(severity, message = nil, progname = nil, &block)
        severity ||= ::Logger::UNKNOWN
        return super unless ErrorTracking.reports?(severity)

        if message.nil? && block
          message = yield
          result = super(severity, message, progname)
        else
          result = super
        end
        ErrorTracking.report(severity, message.nil? ? progname : message)
        result
      end
    end

    class << self
      attr_reader :event_level, :breadcrumb_level

      def enabled?
        @enabled == true
      end

      # Never raises: a bad setting only leaves reporting off.
      def init(component, env = ENV, settings = nil)
        return true if enabled?

        settings = recording_settings if settings.nil?
        dsn = dsn_for(component, env, settings)
        return false if dsn.nil?

        issues = level(setting(component, 'LEVEL', env, settings), ::Logger::ERROR)
        breadcrumbs = level(setting(component, 'BREADCRUMB_LEVEL', env, settings), ::Logger::INFO)
        anonymise = Anonymiser.mode(setting(component, 'ANONYMISE', env, settings))
        anonymiser = Anonymiser.new(anonymise)
        host = server_name(env['BBB_URL'])

        require 'sentry-ruby'
        Sentry.init do |config|
          config.dsn = dsn
          config.environment = setting(component, 'ENVIRONMENT', env, settings) || 'production'
          config.release = "#{component}@#{bbb_release}"
          config.server_name = host unless host.nil?
          config.sample_rate = sample_rate(setting(component, 'SAMPLE_RATE', env, settings))
          # Errors and log entries only: no tracing, profiling, sessions or patches.
          config.traces_sample_rate = nil
          config.auto_session_tracking = false
          config.send_client_reports = false
          config.enabled_patches = []
          config.send_default_pii = false
          # Events are sent before the call returns: resque ends each job with
          # exit!, which would drop a queue. The timeouts keep that short.
          config.background_worker_threads = 0
          config.transport.open_timeout = 1
          config.transport.timeout = 2
          config.sdk_logger = ::Sentry::Logger.new($stderr).tap { |l| l.level = ::Logger::WARN }
          config.before_send = ->(event, _hint) { anonymiser.anonymise_event(event) }
          config.before_breadcrumb = ->(breadcrumb, _hint) { anonymiser.anonymise_breadcrumb(breadcrumb) }
          # sentry-ruby 5.x and 6.x need this switch; 7 always sends the logs.
          config.enable_logs = true if config.respond_to?(:enable_logs=)
          config.before_send_log = ->(log) { anonymiser.anonymise_log(log) }
        end
        tags = { component: component }
        script = script_name($PROGRAM_NAME)
        tags[:script] = script unless script.nil?
        Sentry.set_tags(tags)
        # Runs before the SDK closes, as at_exit blocks run in reverse order.
        at_exit { capture($ERROR_INFO) }

        @event_level = issues
        @breadcrumb_level = breadcrumbs
        @component = component
        @enabled = true

        BigBlueButton.logger.info(
          "Error reporting to #{URI.parse(dsn).host} enabled for #{component} " \
          "(issues from #{::Logger::SEV_LABEL[issues]}, breadcrumbs from #{::Logger::SEV_LABEL[breadcrumbs]}, " \
          "personal data: #{anonymise})"
        )
        true
      rescue Exception => e # rubocop:disable Lint/RescueException
        @enabled = false
        BigBlueButton.logger.warn("Error reporting not started for #{component}: #{e.class}: #{e.message}")
        false
      end

      def watch(logger)
        logger.extend(LoggerHook) if enabled? && logger.respond_to?(:add) && !logger.is_a?(LoggerHook)
        logger
      end

      def tag(**tags)
        Sentry.set_tags(tags) if enabled?
      rescue StandardError
        nil
      end

      # Reports an exception that ended a job or script, unless already reported.
      def capture(error, **tags)
        return unless enabled? && error.is_a?(Exception)
        return if error.is_a?(SystemExit) || error.is_a?(SignalException)
        return if Sentry.exception_captured?(error)

        Sentry.capture_exception(error, level: :fatal, tags: tags)
      rescue StandardError
        nil
      end

      def reports?(severity)
        enabled? && severity >= @breadcrumb_level && !Thread.current[REPORTING]
      end

      # An issue-level record becomes an issue and a log entry; a lower one a breadcrumb.
      def report(severity, message)
        Thread.current[REPORTING] = true
        level = SENTRY_LEVELS.fetch(severity, :fatal)
        text = message_text(message)
        if severity < @event_level
          Sentry.add_breadcrumb(Sentry::Breadcrumb.new(category: 'log', level: level.to_s, message: text))
          return
        end

        send_log(severity, text)

        error = message.is_a?(Exception) ? message : $ERROR_INFO
        if error.is_a?(Exception) && !error.is_a?(SystemExit)
          # The backtrace lines logged after it add nothing to the issue.
          return if Sentry.exception_captured?(error)

          extra = text == error.message ? {} : { log: text }
          Sentry.capture_exception(error, level: level, extra: extra)
        else
          Sentry.capture_message(text, level: level)
        end
      rescue StandardError
        nil
      ensure
        Thread.current[REPORTING] = false
      end

      def setting(component, name, env, settings = {})
        suffix = component.upcase.gsub(/[^A-Z0-9]/, '_')
        key = name.downcase.gsub(/_([a-z])/) { Regexp.last_match(1).upcase }
        value = settings.is_a?(Hash) ? settings[key] : nil
        # YAML reads an unquoted off as false.
        value = 'off' if value == false
        trimmed(env["BBB_SENTRY_#{name}_#{suffix}"]) || trimmed(env["BBB_SENTRY_#{name}"]) || trimmed(value)
      end

      def recording_settings
        settings = BigBlueButton.read_props['sentry']
        settings.is_a?(Hash) ? settings : {}
      rescue StandardError
        {}
      end

      def dsn_for(component, env, settings = {})
        dsn = setting(component, 'DSN', env, settings)
        dsn.nil? || dsn.casecmp?('off') ? nil : dsn
      end

      def level(value, fallback)
        case trimmed(value)&.downcase
        when 'debug' then ::Logger::DEBUG
        when 'info' then ::Logger::INFO
        when 'warn', 'warning' then ::Logger::WARN
        when 'error' then ::Logger::ERROR
        when 'fatal' then ::Logger::FATAL
        else fallback
        end
      end

      def sample_rate(value)
        rate = Float(trimmed(value))
        rate.between?(0.0, 1.0) ? rate : 1.0
      rescue ArgumentError, TypeError
        1.0
      end

      def server_name(bbb_url)
        url = trimmed(bbb_url)
        url.nil? ? nil : URI.parse(url).host
      rescue URI::Error
        nil
      end

      private

      # Flushed now, like the issues: resque ends each job with exit!.
      def send_log(severity, text)
        Sentry.logger.public_send(LOG_LEVELS.fetch(severity, :fatal), text, component: @component)
        Sentry.get_current_client&.log_event_buffer&.flush
      rescue StandardError
        nil
      end

      # process/presentation.rb...; nil for the resque worker.
      def script_name(program)
        return nil unless program.to_s.end_with?('.rb')

        File.join(File.basename(File.dirname(program)), File.basename(program))
      end

      def message_text(message)
        case message
        when String then message
        when Exception then "#{message.message} (#{message.class})"
        else message.inspect
        end
      end

      def bbb_release
        File.foreach(RELEASE_FILE) do |line|
          return line.delete_prefix('BIGBLUEBUTTON_RELEASE=').strip if line.start_with?('BIGBLUEBUTTON_RELEASE=')
        end
        'unknown'
      rescue StandardError
        # A development checkout has no release file.
        'unknown'
      end

      def trimmed(value)
        return nil if value.nil?

        result = value.to_s.strip
        result.empty? ? nil : result
      end
    end
  end
end
