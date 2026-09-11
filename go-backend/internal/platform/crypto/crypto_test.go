package crypto

import (
	"encoding/base64"
	"testing"
)

func TestEncryptDecryptAndHint(t *testing.T) {
	service, err := New([]byte("01234567890123456789012345678901"))
	if err != nil {
		t.Fatal(err)
	}
	ciphertext, err := service.Encrypt("sk-teacher-secret")
	if err != nil {
		t.Fatal(err)
	}
	if ciphertext == "sk-teacher-secret" || ciphertext[:3] != "v1." {
		t.Fatalf("plaintext or invalid ciphertext exposed: %q", ciphertext)
	}
	plaintext, err := service.Decrypt(ciphertext)
	if err != nil || plaintext != "sk-teacher-secret" {
		t.Fatalf("Decrypt() = %q, %v", plaintext, err)
	}
	if got := service.Hint("sk-teacher-secret"); got != "****cret" {
		t.Fatalf("Hint() = %q", got)
	}
	data, err := base64.RawStdEncoding.DecodeString(ciphertext[3:])
	if err != nil {
		t.Fatal(err)
	}
	data[12] ^= 0x01
	tampered := "v1." + base64.RawStdEncoding.EncodeToString(data)
	if _, err := service.Decrypt(tampered); err == nil {
		t.Fatal("tampered ciphertext unexpectedly decrypted")
	}
}
