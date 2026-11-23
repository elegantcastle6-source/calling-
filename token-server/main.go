package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"

	rtctokenbuilder "github.com/AgoraIO/Tools/DynamicKey/AgoraDynamicKey/go/src/rtctokenbuilder2"
)

type tokenResponse struct {
	Token       string `json:"token"`
	RtcToken    string `json:"rtcToken"`
	ChannelName string `json:"channelName"`
	Uid         uint32 `json:"uid"`
	Role        string `json:"role"`
	ExpiresIn   uint32 `json:"expiresIn"`
}

func main() {
	cliMode := flag.Bool("cli", false, "Print a single RTC token to stdout and exit instead of running the HTTP server")
	cliChannel := flag.String("channel", "channelName", "Channel name for CLI mode")
	cliUid := flag.Uint64("uid", 0, "Numeric Agora UID for CLI mode (0 allows any UID)")
	cliRole := flag.String("role", "publisher", "Role for CLI mode: publisher or subscriber")
	flag.Parse()

	appId := strings.TrimSpace(os.Getenv("AGORA_APP_ID"))
	appCertificate := strings.TrimSpace(os.Getenv("AGORA_APP_CERTIFICATE"))
	if appCertificate == "" {
		appCertificate = strings.TrimSpace(os.Getenv("AGORA_APP_CERT"))
	}
	if appId == "" || appCertificate == "" {
		log.Fatal("Need to set environment variables AGORA_APP_ID and AGORA_APP_CERTIFICATE (or AGORA_APP_CERT)")
	}

	const tokenExpirationInSeconds = uint32(3600)
	const privilegeExpirationInSeconds = uint32(3600)

	if *cliMode {
		role := parseRole(*cliRole)
		token, err := rtctokenbuilder.BuildTokenWithUid(
			appId,
			appCertificate,
			strings.TrimSpace(*cliChannel),
			uint32(*cliUid),
			role,
			tokenExpirationInSeconds,
			privilegeExpirationInSeconds,
		)
		if err != nil {
			log.Fatalf("Failed to generate token: %v", err)
		}
		fmt.Printf("Token with int uid %d: %s\n", uint32(*cliUid), token)
		return
	}

	port := strings.TrimSpace(os.Getenv("PORT"))
	if port == "" {
		port = "8080"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	allowedOrigin := strings.TrimSpace(os.Getenv("ALLOWED_ORIGIN"))
	if allowedOrigin == "" {
		allowedOrigin = "*"
	}

	tokenHandler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", allowedOrigin)
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		channelName := strings.TrimSpace(r.URL.Query().Get("channelName"))
		if channelName == "" {
			channelName = strings.TrimSpace(r.URL.Query().Get("channel"))
		}
		if channelName == "" || len(channelName) > 64 {
			http.Error(w, "valid channelName or channel query parameter is required", http.StatusBadRequest)
			return
		}

		var uid uint32 = 0
		if rawUid := strings.TrimSpace(r.URL.Query().Get("uid")); rawUid != "" {
			parsedUid, err := strconv.ParseUint(rawUid, 10, 32)
			if err != nil {
				http.Error(w, "uid must be a valid unsigned 32-bit integer", http.StatusBadRequest)
				return
			}
			uid = uint32(parsedUid)
		}

		roleParam := strings.TrimSpace(r.URL.Query().Get("role"))
		if roleParam == "" {
			roleParam = "publisher"
		}
		agoraRole := parseRole(roleParam)

		token, err := rtctokenbuilder.BuildTokenWithUid(
			appId,
			appCertificate,
			channelName,
			uid,
			agoraRole,
			tokenExpirationInSeconds,
			privilegeExpirationInSeconds,
		)
		if err != nil {
			http.Error(w, "failed to generate rtc token", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(tokenResponse{
			Token:       token,
			RtcToken:    token,
			ChannelName: channelName,
			Uid:         uid,
			Role:        strings.ToLower(roleParam),
			ExpiresIn:   tokenExpirationInSeconds,
		})
	}
	mux.HandleFunc("/rtc-token", tokenHandler)
	mux.HandleFunc("/token", tokenHandler)

	log.Printf("Just Chat Agora RTC token server listening on :%s", port)
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatal(err)
	}
}

// parseRole defaults to RolePublisher so two-way voice callers can both publish microphone audio.
func parseRole(raw string) rtctokenbuilder.Role {
	if strings.EqualFold(strings.TrimSpace(raw), "subscriber") {
		return rtctokenbuilder.RoleSubscriber
	}
	return rtctokenbuilder.RolePublisher
}
