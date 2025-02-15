/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
*/

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const EncryptionConstants = {
  ONE_HOUR_MILLIS: 60 * 60 * 1000,
  /**
   * GCM is an authenticated encryption mode that
   * not only provides confidentiality but also
   * provides integrity in a secured way
   * */
  BLOCK_CIPHER: 'aes-256-gcm',

  /**
   * 128 bit auth tag is recommended for GCM
   */
  AUTH_TAG_BYTE_LEN: 16,

  /**
   * NIST recommends 96 bits or 12 bytes IV for GCM
   * to promote interoperability, efficiency, and
   * simplicity of design
   */
  IV_BYTE_LEN: 12,

  /**
   * Note: 256 (in algorithm name) is key size.
   * Block size for AES is always 128
   */
  KEY_BYTE_LEN: 32,

  /**
   * To prevent rainbow table attacks
   * */
  SALT_BYTE_LEN: 16,
};
// Initialization vector for encryption
const getIV = () => crypto.randomBytes(EncryptionConstants.IV_BYTE_LEN);

/**
 * To prevent rainbow table attacks
 * */
const getSalt = () => crypto.randomBytes(EncryptionConstants.SALT_BYTE_LEN);

/**
 * To be used when key needs to be generated based on password.
 */
const getKeyFromSecret = (password, salt) => {
  return crypto.scryptSync(password, salt, EncryptionConstants.KEY_BYTE_LEN);
};

/**
 * Function to encrypt a given plain text using the secret key.
 * @return Buffer of iv, message and an authtag.
 * The authtag is to prevent from attacks where an malicious
 * user can carefully send a cipher text to backtrack the secret key.
 */
function encrypt(plainText, key) {
  const iv = getIV();
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv, {
    authTagLength: EncryptionConstants.AUTH_TAG_BYTE_LEN,
  });
  let encryptedMessage = cipher.update(plainText);
  encryptedMessage = Buffer.concat([encryptedMessage, cipher.final()]);
  return Buffer.concat([iv, encryptedMessage, cipher.getAuthTag()]);
}

function decrypt(encText, key) {
  const authTag = encText.slice(-EncryptionConstants.SALT_BYTE_LEN);
  const iv = encText.slice(0, EncryptionConstants.IV_BYTE_LEN);
  const encryptedMessage = encText.slice(
    EncryptionConstants.IV_BYTE_LEN,
    -EncryptionConstants.SALT_BYTE_LEN
  );
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv, {
    authTagLength: EncryptionConstants.AUTH_TAG_BYTE_LEN,
  });
  decipher.setAuthTag(authTag);
  let messagetext = decipher.update(encryptedMessage);
  messagetext = Buffer.concat([messagetext, decipher.final()]);
  return messagetext;
}

function getSecretFromCDAPConfig(cdapConfig, logger) {
  let secretKeyFilePath =
    cdapConfig['session.secret.key.path'] ||
    path.resolve(__dirname, 'config', 'development', 'session_secret.key');
  if (!logger) {
    logger = console;
  }
  if (!secretKeyFilePath) {
    logger.warn(
      'Secret key missing. This is required to generate a strong time-based token to prevent cswh'
    );
  }
  let secret;
  try {
    secret = fs.readFileSync(secretKeyFilePath, { encoding: 'utf8' });
  } catch (e) {
    logger.error('Error in generating key for cswh. ' + e);
    secret = '';
  }
  return secret;
}

/**
 * We perform the following steps in generating a secure session token.
 *
 * 1. Get the following from cdap config (cdap-site + cdap-security)
 *    - Secret key, - secret
 *    - environment specific ID, - instancename
 * 2. Create a hash of (creation time + authToken + instance name)
 * 3. Creation time here is epoch time in milliseconds
 * 4. Create a shasum (hex) out of the hash
 * 5. Salt the key to prevent rainbow attacks
 * 6. encrypt "creation-time" & "shasum" using the salted secret key
 * 7. Append salt-encryptedkey and return base64 version of it for transport
 *
 * Example output (not representative of size but the structure)
 * eCEsJ32ACxFp7wkHnsKxZA==-eFM6EJU568EzM+xFl2eeVQ4ruEk6WjjCJ1sISpDU5jpTF4w==
 */
function generateToken(cdapConfig, logger = console, authToken = '') {
  const instanceName = cdapConfig['instance.metadata.id'];
  const secret = getSecretFromCDAPConfig(cdapConfig, logger);
  const salt = getSalt();
  const key = getKeyFromSecret(Buffer.from(secret), salt);
  const creationTime = Date.now();
  const hash = crypto.createHash('sha1');
  hash.update(`${creationTime}-${authToken}-${instanceName}`);
  const shasum = hash.digest('hex');
  const payloadInHex = `${creationTime.toString(16)} ${shasum}`;
  return `${salt.toString('base64')}-${encrypt(payloadInHex, key).toString('base64')}`;
}

/**
 * We exactly do the reverse of encryption.
 *
 * Since we have everything we need to decrypt the cipher text
 * this is self contained.
 * Example input
 * eCEsJ32ACxFp7wkHnsKxZA==-eFM6EJU568EzM+xFl2eeVQ4ruEk6WjjCJ1sISpDU5jpTF4w==
 *
 *  - Split input by '-' (First split is the salt.)
 *  - Again salt the secret key from the previous step
 *  - Decrypt the cipher (Second split) - Should give creation-time & shasum
 *  - Recreate the shasum from creation-time from previous step and values from cdapconfig
 *  - Both the shasum should match
 *  - AND age of token shouldn't be more than an hour.
 */
function validateToken(encryptedToken, cdapConfig, logger = console, authToken = '') {
  let timestamp, shasum, timeinmiilis;
  if (!encryptedToken) {
    return false;
  }
  try {
    let [saltbase64, cipher] = encryptedToken.split('-');
    const salt = Buffer.from(saltbase64, 'base64');
    const secret = getSecretFromCDAPConfig(cdapConfig, logger);
    const key = getKeyFromSecret(Buffer.from(secret), salt);
    let payload = decrypt(Buffer.from(cipher, 'base64'), key);
    let payloadInHex = payload.toString('utf8');
    timestamp = payloadInHex.split(' ')[0];
    shasum = payloadInHex.split(' ')[1];
  } catch (e) {
    logger.error('Validating token failed: ' + e);
    return false;
  }
  timeinmiilis = parseInt(timestamp, 16);
  const instanceName = cdapConfig['instance.metadata.id'];
  const hash = crypto.createHash('sha1');
  hash.update(`${timeinmiilis}-${authToken}-${instanceName}`);
  const shasum1 = hash.digest('hex');
  return shasum == shasum1 && Date.now() - timeinmiilis < EncryptionConstants.ONE_HOUR_MILLIS;
}

module.exports = {
  generateToken,
  validateToken,
};
