#ifndef MESHELL_SHA256_H
#define MESHELL_SHA256_H

/* SHA-256 and the fixed-size HMAC used by the local broker handshake.
 * No external crypto library is loaded into the privileged process.
 */
#include <stdint.h>
#include <stddef.h>
#include <string.h>

typedef struct {
    uint32_t h[8];
    uint64_t bytes;
    uint8_t block[64];
    size_t used;
} sha256_ctx;

static uint32_t ror32(uint32_t n, unsigned bits) {
    return (n >> bits) | (n << (32 - bits));
}

static void sha256_block(sha256_ctx *s, const uint8_t *p) {
    static const uint32_t k[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    };
    uint32_t w[64];
    for (int i = 0; i < 16; ++i)
        w[i] = (uint32_t)p[4*i]<<24 | (uint32_t)p[4*i+1]<<16 | (uint32_t)p[4*i+2]<<8 | p[4*i+3];
    for (int i = 16; i < 64; ++i) {
        uint32_t a = w[i-15], b = w[i-2];
        w[i] = w[i-16] + (ror32(a,7)^ror32(a,18)^(a>>3)) + w[i-7] + (ror32(b,17)^ror32(b,19)^(b>>10));
    }
    uint32_t a=s->h[0],b=s->h[1],c=s->h[2],d=s->h[3],e=s->h[4],f=s->h[5],g=s->h[6],h=s->h[7];
    for (int i = 0; i < 64; ++i) {
        uint32_t t = h + (ror32(e,6)^ror32(e,11)^ror32(e,25)) + ((e&f)^(~e&g)) + k[i] + w[i];
        uint32_t u = (ror32(a,2)^ror32(a,13)^ror32(a,22)) + ((a&b)^(a&c)^(b&c));
        h=g; g=f; f=e; e=d+t; d=c; c=b; b=a; a=t+u;
    }
    s->h[0]+=a; s->h[1]+=b; s->h[2]+=c; s->h[3]+=d;
    s->h[4]+=e; s->h[5]+=f; s->h[6]+=g; s->h[7]+=h;
}

static void sha256_init(sha256_ctx *s) {
    *s = (sha256_ctx){ .h={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,
        0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19} };
}

static void sha256_update(sha256_ctx *s, const void *data, size_t len) {
    const uint8_t *p = data;
    s->bytes += len;
    while (len) {
        size_t n = 64 - s->used;
        if (n > len) n = len;
        memcpy(s->block+s->used, p, n);
        s->used += n; p += n; len -= n;
        if (s->used == 64) { sha256_block(s, s->block); s->used = 0; }
    }
}

static void sha256_final(sha256_ctx *s, uint8_t out[32]) {
    uint64_t bits = s->bytes * 8;
    uint8_t pad[128] = {0x80};
    size_t n = s->used < 56 ? 56-s->used : 120-s->used;
    for (int i = 0; i < 8; ++i) pad[n+i] = (uint8_t)(bits >> (56-8*i));
    sha256_update(s, pad, n+8);
    for (int i = 0; i < 32; ++i) out[i] = (uint8_t)(s->h[i/4] >> (24-8*(i%4)));
    memset(s, 0, sizeof(*s));
}

static void handshake_mac(const uint8_t key[32], const char role[6],
                          const uint8_t nonces[64], uint8_t out[32]) {
    uint8_t pad[64], inner[32];
    sha256_ctx s;
    for (int i = 0; i < 64; ++i) pad[i] = (i < 32 ? key[i] : 0) ^ 0x36;
    sha256_init(&s); sha256_update(&s, pad, 64);
    sha256_update(&s, role, 6); sha256_update(&s, nonces, 64); sha256_final(&s, inner);
    for (int i = 0; i < 64; ++i) pad[i] = (i < 32 ? key[i] : 0) ^ 0x5c;
    sha256_init(&s); sha256_update(&s, pad, 64);
    sha256_update(&s, inner, 32); sha256_final(&s, out);
    memset(pad, 0, sizeof(pad)); memset(inner, 0, sizeof(inner));
}

#endif
