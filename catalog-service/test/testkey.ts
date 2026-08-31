/**
 * A FIXED test keypair, standing in for Google's.
 *
 * Fixed rather than generated per run because two different processes need the
 * two halves: the tests sign tokens inside workerd, and the stand-in key server
 * in `vitest.config.ts` serves the public half from the Node side. A generated
 * key cannot be shared across that boundary.
 *
 * It is a test fixture and signs nothing real. It is not a secret, and it must
 * never be anywhere near a deployed Worker — which it is not: nothing in `src/`
 * imports this file.
 */
export const TEST_KID = "bfg-test-key-1";

export const TEST_PUBLIC_JWK = {
  "kty": "RSA",
  "n": "wHRbHhfzpsSLb1bvK84SLbJ5LvNskYsOw21af75P-3VJWYdMPKx5RBNtqEBJQLl7vm-91LK3hGRqh_le2q20h7gB3PtBHeDLZGcyPZYXZ2A_ndvYsT6Pw2yc7ZHjWDL-Yr0K812oilcvkPfQGQzVrTaLMHZQVX0BODhIL1ANz3IHvA-kdmqBb1CgQWCMr69M1qYFZqh_Gy58SuX4GUXdxLQPz1TA5dPK4kSF1ogQSSwSCoxf-U_6nEN3VKZWK12QAedtg-ae7mZK-1zKB-ShOxTbiT4Ojcj_vH2xsMv0NmhEVFErsCd2vmUKIZIDX_mykY_J0QuKn8trHCkVtv644Q",
  "e": "AQAB",
  "kid": "bfg-test-key-1",
  "alg": "RS256",
  "use": "sig"
};

export const TEST_PRIVATE_JWK = {
  "key_ops": [
    "sign"
  ],
  "ext": true,
  "alg": "RS256",
  "kty": "RSA",
  "n": "wHRbHhfzpsSLb1bvK84SLbJ5LvNskYsOw21af75P-3VJWYdMPKx5RBNtqEBJQLl7vm-91LK3hGRqh_le2q20h7gB3PtBHeDLZGcyPZYXZ2A_ndvYsT6Pw2yc7ZHjWDL-Yr0K812oilcvkPfQGQzVrTaLMHZQVX0BODhIL1ANz3IHvA-kdmqBb1CgQWCMr69M1qYFZqh_Gy58SuX4GUXdxLQPz1TA5dPK4kSF1ogQSSwSCoxf-U_6nEN3VKZWK12QAedtg-ae7mZK-1zKB-ShOxTbiT4Ojcj_vH2xsMv0NmhEVFErsCd2vmUKIZIDX_mykY_J0QuKn8trHCkVtv644Q",
  "e": "AQAB",
  "d": "AqGUnVS2gBEv1tXZbz_h_73w6jNpyJOeIA3eruBrN4Ti-9ZdtHanMccSOLqoRbpDdtcQQVgwKh81VaaTJWo6jaB2Zg8JVN896KGAjlb--urV89KNo963GHTjsFvOP-Zyk-cBv1IIe9u1SsYYkj8arMOSI5d6gbCz05Dxa7QsfCmM3fKRDPN_8uKxAWq8yv8Xb7WT5nS6GS3FXjd2h9Afz57O1MF4iVbJWz6YfJmfEmc6JQNhkN04rX88m5Y3Kpa67h7tlSRCZVoe79SnnE-kcpag9bsLqYpsAAtv2yVOBr8tMrFpmkIHxQwy1Dh9tjfrZzRpL_VHBAENhYs6IyCh",
  "p": "6PivCxTA3oGWEwo5YmXhis1-WtkvOWg_S0KIcQxLz6SxnPHCoBg-o0Szbh8sgO9O4ha7P07BzFBpoqTQK-talyT_pd6n10GwiuyjcleY5LvkEizWt9AlNapQ-JYUs0KenY36AOEPN0AsYi1x32R57Kd7qU5fM2E45bNDct11bUk",
  "q": "03pldPKl82PU3lPMP4HWNKoCEEHdr3Cvm1Jp4q-ll1dC5CIOVJJY9L_dvakKgsXHUQbPP3QLdGByX08HqgUvHAxf3VcBMqSP9BJE7kUUqZLiDvj5OgB5EDkxUqb0ohSWPRkF8J4Ohp0WZ9V2chC_T060A9VlxuDWqBiu3r-aZtk",
  "dp": "hPpt78VI0bjmELglSSUeODxTg0e0zO_UMQEpIF4EZ-F6ADt-_CLanhb41EK3BGGUhTi0aM5rkFPV-CBbMraEXwGfau5Mn3G_c4zfPsUBYI5fAIDXjtQgaSFuVnZUGn5MS2Je8WGFbeQTcXa_KOQRNCPlUh6J-Knl8DcCqO3_uQE",
  "dq": "FtBXbZxXpsAYULu9hwRctWW5v0M7xRdk-l6Tqa7FMGRrhOzfmuQG0LB6HWHFDoKDlAwmA3RjX_SfogSGkSLqCB6VoO0n5ey20t-9q-6r4fdEh9T4Hfe7F7LzzTTRo0KRSpoOd_y1GHSN3Dm9A2UnHkkhRmCZEDFlNtEi3EjTQVE",
  "qi": "bRqy_8rDjM9DvGT8VRFxW5FxSX_jo2C72SiULqIcHH-YIFq17jqz36teHqWzqY0Kq1Vc2AojHeaG_R6t4ZRoBHykiWBL0ioFfpul-i1WcX5WbvoVHSAyoiViD5h5Ry0STnB0PVlUSqil6X5Wpn1PGbCVW2Y2skQoqL8kiiP5-pI"
};
