package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
)

type Service struct{ key []byte }

func New(key []byte) (*Service, error) {
	if len(key) != 32 {
		return nil, errors.New("credential encryption key must be 32 bytes")
	}
	copyKey := append([]byte(nil), key...)
	return &Service{key: copyKey}, nil
}

func (s *Service) Encrypt(plaintext string) (string, error) {
	block, err := aes.NewCipher(s.key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	ciphertext := gcm.Seal(nil, nonce, []byte(plaintext), nil)
	return "v1." + base64.RawStdEncoding.EncodeToString(append(nonce, ciphertext...)), nil
}

func (s *Service) Decrypt(encoded string) (string, error) {
	if len(encoded) < 3 || encoded[:3] != "v1." {
		return "", errors.New("unsupported credential ciphertext")
	}
	data, err := base64.RawStdEncoding.DecodeString(encoded[3:])
	if err != nil {
		return "", errors.New("invalid credential ciphertext")
	}
	block, err := aes.NewCipher(s.key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	if len(data) < gcm.NonceSize()+gcm.Overhead() {
		return "", errors.New("truncated credential ciphertext")
	}
	plaintext, err := gcm.Open(nil, data[:gcm.NonceSize()], data[gcm.NonceSize():], nil)
	if err != nil {
		return "", errors.New("credential decryption failed")
	}
	return string(plaintext), nil
}

func (s *Service) Hint(secret string) string {
	if len(secret) <= 4 {
		return "****"
	}
	return "****" + secret[len(secret)-4:]
}

func ConstantTimeEqual(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

func RedactError(err error) string {
	if err == nil {
		return ""
	}
	return fmt.Sprintf("%T", err)
}
