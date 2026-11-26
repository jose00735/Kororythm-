// Package syncer syncs the library with a phone over Wi-Fi (protocol: ../../../protocol/SYNC.md).
package syncer

import (
	"bytes"
	"crypto/md5"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"strconv"
)

// hashSpan is how much of the audio (after the tags) goes into a fingerprint.
const hashSpan = 4 << 20

var zeroMD5 = make([]byte, md5.Size)

// Fingerprint identifies a song by its audio, not its tags, so the same file retagged on
// one side still matches. FLAC: the decoder MD5 in STREAMINFO ("flac-<md5>"). Otherwise the
// audio bytes between the ID3v2 tag and an ID3v1 / APE tag: "sha-<first 32 hex of the
// SHA-256 of up to 4 MiB>-<audio length>".
func Fingerprint(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return "", err
	}
	return FingerprintReader(f, st.Size())
}

// FingerprintReader is Fingerprint for any seekable source of the given size.
func FingerprintReader(r io.ReaderAt, size int64) (string, error) {
	head := make([]byte, 42)
	n, _ := r.ReadAt(head, 0)
	head = head[:n]
	start, end := int64(0), size

	if n >= 4 && string(head[:4]) == "fLaC" {
		if n >= 42 && head[4]&0x7f == 0 && !bytes.Equal(head[26:42], zeroMD5) {
			return "flac-" + hex.EncodeToString(head[26:42]), nil
		}
		// No MD5 written by the encoder: skip the metadata blocks and hash the frames.
		pos := int64(4)
		for {
			h := make([]byte, 4)
			if _, err := r.ReadAt(h, pos); err != nil {
				return "", errors.New("flac: bad metadata")
			}
			l := int64(h[1])<<16 | int64(h[2])<<8 | int64(h[3])
			pos += 4 + l
			if h[0]&0x80 != 0 {
				break
			}
		}
		start = pos
	} else {
		if n >= 10 && string(head[:3]) == "ID3" {
			start = 10 + (int64(head[6]&0x7f)<<21 | int64(head[7]&0x7f)<<14 | int64(head[8]&0x7f)<<7 | int64(head[9]&0x7f))
			if head[5]&0x10 != 0 {
				start += 10
			}
		}
		if end-start >= 128 {
			t := make([]byte, 3)
			r.ReadAt(t, end-128)
			if string(t) == "TAG" {
				end -= 128
			}
		}
		if end-start >= 32 {
			ft := make([]byte, 32)
			r.ReadAt(ft, end-32)
			if string(ft[:8]) == "APETAGEX" {
				tagSize := int64(binary.LittleEndian.Uint32(ft[12:16]))
				flags := binary.LittleEndian.Uint32(ft[20:24])
				end -= tagSize
				if flags&(1<<31) != 0 {
					end -= 32
				}
			}
		}
	}
	if start > end {
		start = end
	}
	span := end - start
	if span > hashSpan {
		span = hashSpan
	}
	h := sha256.New()
	if _, err := io.Copy(h, io.NewSectionReader(r, start, span)); err != nil {
		return "", err
	}
	return "sha-" + hex.EncodeToString(h.Sum(nil))[:32] + "-" + strconv.FormatInt(end-start, 10), nil
}
