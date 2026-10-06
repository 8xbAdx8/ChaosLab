package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"reflect"
	"regexp"
	"strings"
	"unicode/utf8"
)

const requestLimit = 4096

var uidPattern = regexp.MustCompile(`^[0-9a-f]{16}$`)
var executionPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var hashPattern = regexp.MustCompile(`^[0-9a-f]{64}$`)
var invalid = errors.New("invalid input")

type Request struct {
	Operation   string `json:"operation"`
	ExecutionID string `json:"executionId,omitempty"`
	NativeUID   string `json:"nativeUid,omitempty"`
}

// Strict decoding applies to requests AND trusted configuration/state. In
// particular encoding/json alone would accept duplicate keys and bad UTF-8.
func strictJSON(data []byte, dst any) error {
	if !utf8.Valid(data) {
		return invalid
	}
	d := json.NewDecoder(bytes.NewReader(data))
	var value func(int) error
	value = func(depth int) error {
		if depth > 16 {
			return invalid
		}
		token, err := d.Token()
		if err != nil {
			return invalid
		}
		delim, ok := token.(json.Delim)
		if !ok {
			return nil
		}
		switch delim {
		case '{':
			seen := map[string]bool{}
			for d.More() {
				k, e := d.Token()
				if e != nil {
					return invalid
				}
				key, ok := k.(string)
				if !ok || seen[key] {
					return invalid
				}
				seen[key] = true
				if value(depth+1) != nil {
					return invalid
				}
			}
		case '[':
			for d.More() {
				if value(depth+1) != nil {
					return invalid
				}
			}
		default:
			return invalid
		}
		_, err = d.Token()
		return err
	}
	if value(0) != nil {
		return invalid
	}
	if _, err := d.Token(); err != io.EOF {
		return invalid
	}
	typ := reflect.TypeOf(dst)
	if typ.Kind() == reflect.Pointer && typ.Elem().Kind() == reflect.Struct {
		var fields map[string]json.RawMessage
		if json.Unmarshal(data, &fields) != nil || fields == nil {
			return invalid
		}
		allowed := map[string]bool{}
		typ = typ.Elem()
		for i := 0; i < typ.NumField(); i++ {
			f := typ.Field(i)
			name := strings.Split(f.Tag.Get("json"), ",")[0]
			if name == "" {
				name = f.Name
			}
			allowed[name] = true
		}
		for name := range fields {
			if !allowed[name] {
				return invalid
			}
		}
	}
	d = json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if err := d.Decode(dst); err != nil {
		return invalid
	}
	return nil
}

func readRequest(r io.Reader) (Request, error) {
	var q Request
	b, e := io.ReadAll(io.LimitReader(r, requestLimit+1))
	if e != nil || len(b) > requestLimit {
		return q, invalid
	}
	if strictJSON(b, &q) != nil {
		return q, invalid
	}
	switch q.Operation {
	case "preflight":
		if q.ExecutionID != "" || q.NativeUID != "" {
			return q, invalid
		}
	case "create-cpu", "status", "destroy", "observe":
		if !executionPattern.MatchString(q.ExecutionID) || !uidPattern.MatchString(q.NativeUID) {
			return q, invalid
		}
	default:
		return q, invalid
	}
	return q, nil
}

// Used by the platform-side proof fixture, never derived from execution UUID.
// Production Java integration is deliberately not part of this module.
func newNativeUID() (string, error) {
	var b [8]byte
	_, e := rand.Read(b[:])
	return hex.EncodeToString(b[:]), e
}

type Result struct {
	Version         int    `json:"version"`
	Code            string `json:"code"`
	Outcome         string `json:"outcome,omitempty"`
	ExitCode        *int   `json:"exitCode,omitempty"`
	CleanupComplete bool   `json:"cleanupComplete"`
	Handoff         bool   `json:"handoff"`
	NativeUID       string `json:"nativeUid,omitempty"`
	// Raw output is carried only after strict JSON validation. Stderr, paths and
	// OS errors are never reflected. This is transport, NOT recovery verification.
	Response json.RawMessage `json:"response,omitempty"`
}

func result(code string) Result { return Result{Version: 1, Code: code} }
