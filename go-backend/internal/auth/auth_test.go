package auth

import "testing"

func TestPasswordAndOpaqueSessionToken(t *testing.T) {
	hash, err := HashPassword("long-enough-password")
	if err != nil {
		t.Fatal(err)
	}
	if !CheckPassword(hash, "long-enough-password") || CheckPassword(hash, "wrong-password") {
		t.Fatal("password verification contract failed")
	}
	token, err := NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if len(token) != 64 || token == TokenHash(token) || Bearer("Bearer "+token) != token {
		t.Fatal("session token contract failed")
	}
}
