package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/platform/storage"
	"lessonforge.local/backend/internal/rag"
)

func main() {
	dbURL := flag.String("db", "", "PostgreSQL URL used for the verification database")
	storageRoot := flag.String("storage", "", "shared file storage root")
	ragURL := flag.String("rag-url", "", "Java RAG service base URL")
	ragToken := flag.String("rag-token", "", "internal RAG service token")
	missionID := flag.Int64("mission-id", 0, "Mission id to verify")
	fileID := flag.Int64("file-id", 0, "Mission file id or file object id to read")
	query := flag.String("query", "", "material search query")
	locator := flag.String("locator", "", "bounded material locator, for example chunk:1")
	flag.Parse()

	for name, value := range map[string]string{
		"db": *dbURL, "storage": *storageRoot, "rag-url": *ragURL,
		"rag-token": *ragToken, "query": *query,
	} {
		if strings.TrimSpace(value) == "" {
			fail(fmt.Sprintf("-%s is required", name))
		}
	}
	if *missionID <= 0 || *fileID <= 0 {
		fail("-mission-id and -file-id must be positive")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	defer cancel()
	pool, err := database.Open(ctx, *dbURL)
	if err != nil {
		fail(err.Error())
	}
	defer pool.Close()
	files, err := storage.New(*storageRoot, 200*1024*1024)
	if err != nil {
		fail(err.Error())
	}
	client := rag.NewJavaClient(*ragURL, 90*time.Second, 4*1024*1024)
	client.SetServiceBearerToken(*ragToken)
	client.ConfigureResources(database.NewStore(pool), files)
	hits, err := client.Search(ctx, *missionID, *query, []int64{*fileID})
	if err != nil {
		fail(err.Error())
	}
	content, err := client.Read(ctx, *missionID, *fileID, *locator)
	if err != nil {
		fail(err.Error())
	}
	prefix := content
	if len(prefix) > 160 {
		prefix = prefix[:160]
	}
	result := map[string]any{
		"missionId":  *missionID,
		"fileId":     *fileID,
		"hitCount":   len(hits),
		"hits":       hits,
		"readBytes":  len(content),
		"readPrefix": prefix,
	}
	encoded, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		fail(err.Error())
	}
	fmt.Println(string(encoded))
}

func fail(message string) {
	fmt.Fprintln(os.Stderr, message)
	os.Exit(1)
}
