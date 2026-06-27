#include <stddef.h>
#include <stdint.h>

typedef uint32_t CC_LONG;

enum {
  CC_MD5_DIGEST_LENGTH = 16,
  CC_SHA1_DIGEST_LENGTH = 20,
  CC_SHA256_DIGEST_LENGTH = 32,
  CC_SHA512_DIGEST_LENGTH = 64,
};

typedef enum {
  kCCHmacAlgSHA1 = 0,
  kCCHmacAlgMD5 = 1,
  kCCHmacAlgSHA256 = 2,
  kCCHmacAlgSHA384 = 3,
  kCCHmacAlgSHA512 = 4,
} CCHmacAlgorithm;

unsigned char *CC_MD5(const void *data, CC_LONG len, unsigned char *md);
unsigned char *CC_SHA1(const void *data, CC_LONG len, unsigned char *md);
unsigned char *CC_SHA256(const void *data, CC_LONG len, unsigned char *md);
unsigned char *CC_SHA512(const void *data, CC_LONG len, unsigned char *md);

void CCHmac(
  CCHmacAlgorithm algorithm,
  const void *key,
  size_t keyLength,
  const void *data,
  size_t dataLength,
  void *macOut
);

/* CommonCryptor: just enough of CommonCrypto/CommonCryptor.h for AES-128-CBC decryption. */
typedef int32_t CCCryptorStatus;
typedef uint32_t CCOperation;
typedef uint32_t CCAlgorithm;
typedef uint32_t CCOptions;

enum {
  kCCSuccess = 0,
};

enum {
  kCCEncrypt = 0,
  kCCDecrypt = 1,
};

enum {
  kCCAlgorithmAES = 0,
};

enum {
  kCCOptionPKCS7Padding = 0x0001,
  kCCOptionECBMode = 0x0002,
};

enum {
  kCCBlockSizeAES128 = 16,
  kCCKeySizeAES128 = 16,
};

CCCryptorStatus CCCrypt(
  CCOperation op,
  CCAlgorithm alg,
  CCOptions options,
  const void *key,
  size_t keyLength,
  const void *iv,
  const void *dataIn,
  size_t dataInLength,
  void *dataOut,
  size_t dataOutAvailable,
  size_t *dataOutMoved
);
