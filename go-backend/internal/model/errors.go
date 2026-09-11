package model

import "errors"

// ErrCommitAmbiguous means a transaction outcome could not be confirmed.
// Callers must reconcile durable state before applying a compensating failure.
var ErrCommitAmbiguous = errors.New("DATABASE_COMMIT_AMBIGUOUS")

// ErrTimeout is returned when the provider transport reaches its configured
// deadline. Keeping a sentinel lets the Agent worker preserve the distinction
// between a provider timeout and an unrelated provider failure.
var ErrTimeout = errors.New("MODEL_TIMEOUT")

// ErrRetryableRAGBinding marks a transient local binding persistence failure
// after the Java LessonForge intake has already accepted a material. The
// parser worker must keep the MissionFile pending so the next pass can repeat
// the same missionFileId+sourceSha256 operation instead of making a durable
// FAILED state that has no automatic recovery path.
var ErrRetryableRAGBinding = errors.New("RAG_BINDING_RETRYABLE")

// ErrQuestionAnswerInvalid means the submitted answer does not match the
// server-owned question type/options. It is intentionally distinct from a
// missing or unauthorized question so HTTP callers can receive a safe 400
// without revealing whether another teacher's question exists.
var ErrQuestionAnswerInvalid = errors.New("QUESTION_ANSWER_INVALID")
