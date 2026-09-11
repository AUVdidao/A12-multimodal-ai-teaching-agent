package config

import "testing"

func TestGenerationWorkerIsExplicitOptIn(t *testing.T) {
	t.Setenv("LESSONFORGE_ENCRYPTION_KEY", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
	t.Setenv("LESSONFORGE_ENABLE_GENERATION_WORKER", "")
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.EnableGenerationWorker {
		t.Fatal("generation worker must be disabled by default")
	}

	t.Setenv("LESSONFORGE_ENABLE_GENERATION_WORKER", "true")
	cfg, err = Load()
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.EnableGenerationWorker {
		t.Fatal("explicit generation opt-in was ignored")
	}

	t.Setenv("LESSONFORGE_ENABLE_GENERATION_WORKER", "not-a-bool")
	cfg, err = Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.EnableGenerationWorker {
		t.Fatal("invalid generation flag must fail closed to disabled")
	}
}
