// Package errortracking adds optional error reporting to a Sentry-compatible server: call Init
// at start-up and defer Close. Settings: BBB_SENTRY_<KEY>, BBB_SENTRY_<KEY>_<COMPONENT> for one
// component, else the sentry block of the configuration.
package errortracking

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/getsentry/sentry-go"
	"github.com/getsentry/sentry-go/attribute"
	log "github.com/sirupsen/logrus"
)

const (
	releaseFile  = "/etc/bigbluebutton/bigbluebutton-release"
	flushTimeout = 2 * time.Second
	// fieldsContext carries the fields of a log record.
	fieldsContext = "fields"
	// maxErrorDepth is the SDK's default depth of a reported chain of wrapped errors.
	maxErrorDepth = 100
)

// Config is the sentry block of the configuration; the environment overrides it.
type Config struct {
	DSN             string `yaml:"dsn"`
	Level           string `yaml:"level"`
	BreadcrumbLevel string `yaml:"breadcrumbLevel"`
	Anonymise       string `yaml:"anonymise"`
	Environment     string `yaml:"environment"`
	SampleRate      string `yaml:"sampleRate"`
}

func (c Config) values() map[string]string {
	return map[string]string{
		"DSN":              c.DSN,
		"LEVEL":            c.Level,
		"BREADCRUMB_LEVEL": c.BreadcrumbLevel,
		"ANONYMISE":        c.Anonymise,
		"ENVIRONMENT":      c.Environment,
		"SAMPLE_RATE":      c.SampleRate,
	}
}

// Init returns whether reporting started; it never panics.
func Init(component string, config Config) bool {
	return initWith(component, environ(), config.values())
}

func initWith(component string, env, file map[string]string) (enabled bool) {
	defer func() {
		if r := recover(); r != nil {
			log.Warnf("Error reporting not started for %s: %v", component, r)
			enabled = false
		}
	}()

	dsn := dsnFor(component, env, file)
	if dsn == "" {
		return false
	}
	issues := level(setting(component, "LEVEL", env, file), log.ErrorLevel)
	breadcrumbs := level(setting(component, "BREADCRUMB_LEVEL", env, file), log.InfoLevel)
	anonymise := parseMode(setting(component, "ANONYMISE", env, file))
	s := newAnonymiser(anonymise)
	rate := sampleRate(setting(component, "SAMPLE_RATE", env, file))
	environment := setting(component, "ENVIRONMENT", env, file)
	if environment == "" {
		environment = "production"
	}

	err := sentry.Init(sentry.ClientOptions{
		Dsn:         dsn,
		Environment: environment,
		Release:     component + "@" + bbbRelease(),
		ServerName:  serverName(env["BBB_URL"]),
		// The SDK reads 0 as 1; BeforeSend drops everything instead.
		SampleRate: rate,
		Tags:       map[string]string{"component": component},
		BeforeSend: func(event *sentry.Event, _ *sentry.EventHint) *sentry.Event {
			if rate == 0 {
				return nil
			}
			return s.anonymiseEvent(event)
		},
		BeforeBreadcrumb: func(breadcrumb *sentry.Breadcrumb, _ *sentry.BreadcrumbHint) *sentry.Breadcrumb {
			return s.anonymiseBreadcrumb(breadcrumb)
		},
		// The SDK sends log entries once a logger exists (the hook's): no switch to turn on.
		BeforeSendLog: s.anonymiseLog,
		// Errors only: the global tags and the runtime contexts, no module list.
		Integrations: func(defaults []sentry.Integration) []sentry.Integration {
			var kept []sentry.Integration
			for _, integration := range defaults {
				if name := integration.Name(); name == "GlobalTags" || name == "Environment" {
					kept = append(kept, integration)
				}
			}
			return kept
		},
		DisableClientReports: true,
		HTTPClient:           &http.Client{Timeout: 5 * time.Second},
	})
	if err != nil {
		log.Warnf("Error reporting not started for %s: %v", component, err)
		return false
	}

	reporting = newHook(component, issues, breadcrumbs)
	log.AddHook(reporting)
	// log.Fatal exits without running deferred calls.
	log.RegisterExitHandler(Flush)

	dsnHost := ""
	if u, err := url.Parse(dsn); err == nil {
		dsnHost = u.Hostname()
	}
	log.Infof("Error reporting to %s enabled for %s (issues from %s, breadcrumbs from %s, personal data: %s)",
		dsnHost, component, issues, breadcrumbs, anonymise)
	return true
}

// AddHook reports the records of a logger other than the standard one.
func AddHook(logger *log.Logger) {
	if reporting != nil {
		logger.AddHook(reporting)
	}
}

// Flush waits up to 2 s for the pending events to be sent.
func Flush() {
	sentry.Flush(flushTimeout)
}

// Close reports a panic of the calling goroutine, then flushes. Defer it after Init.
func Close() {
	if r := recover(); r != nil {
		sentry.CurrentHub().Recover(r)
		Flush()
		panic(r)
	}
	Flush()
}

func environ() map[string]string {
	env := make(map[string]string)
	for _, pair := range os.Environ() {
		if name, value, ok := strings.Cut(pair, "="); ok {
			env[name] = value
		}
	}
	return env
}

var notUpperAlnum = regexp.MustCompile(`[^A-Z0-9]`)

func setting(component, name string, env, file map[string]string) string {
	suffix := notUpperAlnum.ReplaceAllString(strings.ToUpper(component), "_")
	if value := strings.TrimSpace(env["BBB_SENTRY_"+name+"_"+suffix]); value != "" {
		return value
	}
	if value := strings.TrimSpace(env["BBB_SENTRY_"+name]); value != "" {
		return value
	}
	return strings.TrimSpace(file[name])
}

func dsnFor(component string, env, file map[string]string) string {
	dsn := setting(component, "DSN", env, file)
	if strings.EqualFold(dsn, "off") {
		return ""
	}
	return dsn
}

func level(value string, fallback log.Level) log.Level {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "debug":
		return log.DebugLevel
	case "info":
		return log.InfoLevel
	case "warn", "warning":
		return log.WarnLevel
	case "error":
		return log.ErrorLevel
	case "fatal":
		return log.FatalLevel
	default:
		return fallback
	}
}

func sampleRate(value string) float64 {
	rate, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
	if err != nil || !(rate >= 0 && rate <= 1) {
		return 1.0
	}
	return rate
}

func serverName(bbbURL string) string {
	u, err := url.Parse(strings.TrimSpace(bbbURL))
	if err != nil {
		return ""
	}
	return u.Hostname()
}

func bbbRelease() string {
	// A development checkout has no release file.
	content, _ := os.ReadFile(releaseFile)
	for _, line := range strings.Split(string(content), "\n") {
		if version, ok := strings.CutPrefix(line, "BIGBLUEBUTTON_RELEASE="); ok {
			return strings.TrimSpace(version)
		}
	}
	return "unknown"
}

// reporting is the hook of the standard logger, nil while reporting is off.
var reporting *hook

// hook sends issues and log entries from the issues level, breadcrumbs below it.
type hook struct {
	issues, breadcrumbs log.Level
	// logs sends the log entries; nil sends none.
	logs sentry.Logger
}

func newHook(component string, issues, breadcrumbs log.Level) *hook {
	logs := sentry.NewLogger(context.Background())
	logs.SetAttributes(attribute.String("component", component))
	return &hook{issues: issues, breadcrumbs: breadcrumbs, logs: logs}
}

func (h *hook) Levels() []log.Level {
	// logrus levels count down from panic (0): the least severe level is the largest.
	return log.AllLevels[:max(h.issues, h.breadcrumbs)+1]
}

func (h *hook) Fire(entry *log.Entry) error {
	// Reporting must not break logging.
	defer func() { _ = recover() }()

	fields := make(map[string]any, len(entry.Data))
	for key, value := range entry.Data {
		fields[key] = fieldValue(value)
	}
	if entry.Level <= h.issues {
		event := sentry.NewEvent()
		event.Level = sentryLevel(entry.Level)
		event.Message = entry.Message
		event.Timestamp = entry.Time
		if err, ok := entry.Data[log.ErrorKey].(error); ok && err != nil {
			event.SetException(err, maxErrorDepth)
		}
		if len(fields) > 0 {
			event.Contexts = map[string]sentry.Context{fieldsContext: fields}
		}
		sentry.CaptureEvent(event)
		h.sendLog(entry)
	} else if entry.Level <= h.breadcrumbs {
		sentry.AddBreadcrumb(&sentry.Breadcrumb{
			Type:      "default",
			Category:  "log",
			Message:   entry.Message,
			Data:      fields,
			Level:     sentryLevel(entry.Level),
			Timestamp: entry.Time,
		})
	}
	return nil
}

// The fields stay on the issue, where the anonymiser reads their names.
func (h *hook) sendLog(entry *log.Entry) {
	if h.logs == nil {
		return
	}
	var logEntry sentry.LogEntry
	switch entry.Level {
	case log.PanicLevel, log.FatalLevel:
		// LFatal neither exits nor panics: logrus does that itself.
		logEntry = h.logs.LFatal()
	case log.ErrorLevel:
		logEntry = h.logs.Error()
	case log.WarnLevel:
		logEntry = h.logs.Warn()
	case log.InfoLevel:
		logEntry = h.logs.Info()
	default:
		logEntry = h.logs.Debug()
	}
	// Emit, not Emitf: the message is already formatted, so no raw arguments are attached.
	logEntry.Emit(entry.Message)
}

// A struct would be sent as JSON without being anonymised, so it becomes text.
func fieldValue(value any) any {
	switch v := value.(type) {
	case nil, string, bool, int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64, float32, float64:
		return v
	case error, fmt.Stringer:
		return fmt.Sprint(v)
	default:
		if text, err := json.Marshal(v); err == nil {
			return string(text)
		}
		return fmt.Sprintf("%+v", v)
	}
}

func sentryLevel(l log.Level) sentry.Level {
	switch l {
	case log.PanicLevel, log.FatalLevel:
		return sentry.LevelFatal
	case log.ErrorLevel:
		return sentry.LevelError
	case log.WarnLevel:
		return sentry.LevelWarning
	case log.InfoLevel:
		return sentry.LevelInfo
	default:
		return sentry.LevelDebug
	}
}
