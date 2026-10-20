package errortracking

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/getsentry/sentry-go"
	"github.com/getsentry/sentry-go/attribute"
)

// filtered replaces secrets, and personal data in mask mode.
const filtered = "[Filtered]"

// mode says what happens to personal data: masked or kept.
type mode int

const (
	modeMask mode = iota
	modeOff
)

func parseMode(value string) mode {
	if strings.EqualFold(strings.TrimSpace(value), "off") {
		return modeOff
	}
	return modeMask
}

func (m mode) String() string {
	if m == modeOff {
		return "off"
	}
	return "mask"
}

var (
	secretName = regexp.MustCompile(`(?i)pass|secret|token|checksum|salt|api[_-]?key|authori[sz]ation|cookie|pw$`)
	// fullName, email, remoteAddress, ip, chat message...
	personalName = regexp.MustCompile(`(?i)name$|e-?mail|address$|^ip$|^message$|^text$|^msg$`)

	// The bounded name keeps the scan linear. A value stops at '?' and '=' so a URL value
	// (url=https://h/p?token=x) leaves its own query pairs to be matched; \x0B widens \s.
	queryPair = regexp.MustCompile(`([\w.\-\[\]]{1,256})=([^&\s\x0B"'<>,;?=]*)`)
	jsonPair  = regexp.MustCompile(`"([\w.\-]{1,256})"([\s\x0B]*:[\s\x0B]*)"((?:[^"\\]|\\[^\n\r\x{85}\x{2028}\x{2029}])*)"`)

	// An IPv4 address is never next to a digit or a dot, so it is a whole run
	// of them; Go regexps have no lookaround to say so in one pattern.
	digitsAndDots = regexp.MustCompile(`[0-9.]+`)
	ipv4          = regexp.MustCompile(`^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)$`)
)

// anonymiser filters secrets and masks personal data, by the name of each pair.
type anonymiser struct {
	mode mode
}

func newAnonymiser(m mode) *anonymiser {
	return &anonymiser{mode: m}
}

func (s *anonymiser) isSecret(name string) bool {
	return secretName.MatchString(name)
}

func (s *anonymiser) isPersonal(name string) bool {
	return s.mode != modeOff && personalName.MatchString(name)
}

// isFiltered says whether the value of a pair becomes filtered.
func (s *anonymiser) isFiltered(name string) bool {
	return s.isSecret(name) || s.isPersonal(name)
}

func (s *anonymiser) anonymiseText(text string) string {
	if text == "" {
		return text
	}
	result := replacePairs(queryPair, text, func(g []string) (string, bool) {
		return g[1] + "=" + filtered, s.isFiltered(g[1])
	})
	result = replacePairs(jsonPair, result, func(g []string) (string, bool) {
		return `"` + g[1] + `"` + g[2] + `"` + filtered + `"`, s.isFiltered(g[1])
	})
	if s.mode != modeOff {
		result = digitsAndDots.ReplaceAllStringFunc(result, func(run string) string {
			if ipv4.MatchString(run) {
				return filtered
			}
			return run
		})
	}
	return result
}

func replacePairs(re *regexp.Regexp, text string, masked func(groups []string) (string, bool)) string {
	var out strings.Builder
	last, changed := 0, false
	for _, loc := range re.FindAllStringSubmatchIndex(text, -1) {
		groups := make([]string, len(loc)/2)
		for i := range groups {
			if loc[2*i] >= 0 {
				groups[i] = text[loc[2*i]:loc[2*i+1]]
			}
		}
		if replaced, ok := masked(groups); ok {
			out.WriteString(text[last:loc[0]])
			out.WriteString(replaced)
			last, changed = loc[1], true
		}
	}
	if !changed {
		return text
	}
	out.WriteString(text[last:])
	return out.String()
}

func (s *anonymiser) anonymiseValue(name string, value any) any {
	if name != "" && value != nil && s.isFiltered(name) {
		return filtered
	}
	switch v := value.(type) {
	case string:
		return s.anonymiseText(v)
	case map[string]any:
		return s.anonymiseMap(v)
	case map[string]string:
		out := make(map[string]string, len(v))
		for key, item := range v {
			out[key] = fmt.Sprint(s.anonymiseValue(key, item))
		}
		return out
	case []any:
		out := make([]any, len(v))
		for i, item := range v {
			out[i] = s.anonymiseValue("", item)
		}
		return out
	case []string:
		out := make([]string, len(v))
		for i, item := range v {
			out[i] = s.anonymiseText(item)
		}
		return out
	default:
		return value
	}
}

func (s *anonymiser) anonymiseMap(m map[string]any) map[string]any {
	out := make(map[string]any, len(m))
	for key, item := range m {
		out[key] = s.anonymiseValue(key, item)
	}
	return out
}

func (s *anonymiser) anonymiseEvent(event *sentry.Event) *sentry.Event {
	event.Message = s.anonymiseText(event.Message)
	for i := range event.Exception {
		event.Exception[i].Value = s.anonymiseText(event.Exception[i].Value)
	}
	for _, breadcrumb := range event.Breadcrumbs {
		s.anonymiseBreadcrumb(breadcrumb)
	}
	if fields, ok := event.Contexts[fieldsContext]; ok {
		event.Contexts[fieldsContext] = s.anonymiseMap(fields)
	}
	if r := event.Request; r != nil {
		r.URL = s.anonymiseText(r.URL)
		r.QueryString = s.anonymiseText(r.QueryString)
		r.Headers, _ = s.anonymiseValue("", r.Headers).(map[string]string)
		r.Env, _ = s.anonymiseValue("", r.Env).(map[string]string)
		if r.Cookies != "" {
			r.Cookies = filtered
		}
		r.Data = ""
	}
	return event
}

func (s *anonymiser) anonymiseBreadcrumb(breadcrumb *sentry.Breadcrumb) *sentry.Breadcrumb {
	breadcrumb.Message = s.anonymiseText(breadcrumb.Message)
	if breadcrumb.Data != nil {
		breadcrumb.Data = s.anonymiseMap(breadcrumb.Data)
	}
	return breadcrumb
}

// The raw arguments of the log call are dropped; the formatted body is enough.
func (s *anonymiser) anonymiseLog(entry *sentry.Log) *sentry.Log {
	entry.Body = s.anonymiseText(entry.Body)
	for name, value := range entry.Attributes {
		if strings.HasPrefix(name, "sentry.message.parameter") {
			delete(entry.Attributes, name)
		} else if value.Type() == attribute.STRING {
			entry.Attributes[name] = attribute.StringValue(s.anonymiseText(value.AsString()))
		}
	}
	return entry
}
