/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.alloydb;

class TestKeys {

  static final String CLIENT_PUBLIC_KEY =
      "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAsO6fG3bOvzyCHXB0F+zz\n"
          + "PcGHW95u4WRGn9S0yac9KAAMoZ39Hx6d9nfXFPJrHXHT6LxfRqn0c1Ly5uW+x6ez\n"
          + "xwQmvwyDDrBw1udWOPZrchF/krN3seFMnsXpv7qhN68ORo91L2b+uSQL6mWd6MQN\n"
          + "J/dXID5dLus5gGIBGw7T+vSVZ4UkmAasDQKyeTRJk68LAbYNnbIRA/wcpz4Wk0Sc\n"
          + "lAN8eReH4YeNPC4wlKY6B1dpZGMarLSWmHXfI7+jxckiUXv/OjxnuVyi6je37SUJ\n"
          + "dfFbSc+UWy4D837DNihX7w8PeFowDVINZo2vC3lNfUxjawPZejucVJv+FL0lnFXa\n"
          + "iwIDAQAB";

  static final String CLIENT_PRIVATE_KEY =
      "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCw7p8bds6/PIId\n"
          + "cHQX7PM9wYdb3m7hZEaf1LTJpz0oAAyhnf0fHp32d9cU8msdcdPovF9GqfRzUvLm\n"
          + "5b7Hp7PHBCa/DIMOsHDW51Y49mtyEX+Ss3ex4Uyexem/uqE3rw5Gj3UvZv65JAvq\n"
          + "ZZ3oxA0n91cgPl0u6zmAYgEbDtP69JVnhSSYBqwNArJ5NEmTrwsBtg2dshED/Byn\n"
          + "PhaTRJyUA3x5F4fhh408LjCUpjoHV2lkYxqstJaYdd8jv6PFySJRe/86PGe5XKLq\n"
          + "N7ftJQl18VtJz5RbLgPzfsM2KFfvDw94WjANUg1mja8LeU19TGNrA9l6O5xUm/4U\n"
          + "vSWcVdqLAgMBAAECggEAAohHyrLWnrIVAqrbXoRLrvSYJV53o841nJAEzmYQYAQw\n"
          + "KhgCyXE8vYxVjb0yf0djyw73JiHEl+n2BAwBwQXLcuvCSjW5onowA0NoXoRYYVeS\n"
          + "xdy/t9ILsLIQeGgJaqycvbHL8ZO9zZSQfxhZPD2iGpJVsuZxmvxO5GGCtoptykez\n"
          + "xUonWlxggADXCvBMLVAei+/FFpiGVw7rnQtVZSPoOIFirnW7VLsZ5m7l8lzweBX/\n"
          + "4GFCJ5wrZjNpGBISi7jUi86TUffElfMhnB0UmCr4ejl7BnjhOnQfgIxUnIzguTzv\n"
          + "fWrAkCpRlfJ2ayiwb9piRBYKXdAzE88IJiN3vDXl8QKBgQDpsCL8qj0Ma14/CUKn\n"
          + "VIjzCrnPqXtuHwIwsOGJj2uEIVOE8seg+qh9AKpEHfSVQV7KWVuuoOjjhnt3kUCq\n"
          + "qEL2eAheD3hj026O+KhKEdQd+4FXsF1hVlC8enLAKRwRPcFY2HfKXmn/Laie/ujb\n"
          + "apUVZSxOfN9t2ScPhNokdLI+/wKBgQDB0z30erBHpaS2FybSYxfwph1COIyfu7Gv\n"
          + "7epZk5hQjqH/NWoo0o6nAxNgYkGE1aw2rwSMA4UCqYBUEERmVwIBGY4Rdlc91aHH\n"
          + "u1VganIKQ91jInp3+h/iTxAP6SlnaVhueie5fhX5aF6vic48br1GzHHkjDdd9+EF\n"
          + "KwKypDDwdQKBgQDDg2ESQaAH9wCH/shsVParWOryydqB3KGpeSOJQpvylStaTQEm\n"
          + "NvCmfNr3WJScF3AmHbLuHKQcUBSWickvvs0fhneBsrHH20phhbrPFbGBUD37zouh\n"
          + "92Re/JdrHDnmVuOf4KQAhRNrspikRaMuiDKpDteN5z3LmuXqPv1/iL7kowKBgArc\n"
          + "5PcDvUd2sCGIcKOP7DKPjMo+UxtMsKu0gNLeY8X4CHo+KyH4kwgnMvnO5+8i7pDu\n"
          + "BCo4tEau60NK2hqGO9WF8iFmaYNMqtF/3UwXCycqIIlC0GxS92B+n97UaX6jN9KR\n"
          + "RBKwT1j4EX5zEbzvU+pY4PU3Ko84qkLY40DR1PBVAoGAPKNjVTg0hfFNJ1jg00q7\n"
          + "e5q9Vc0yBohB2XRldr2EvtCzbViwPGQKjQo5YEEsM8M5iVRicXlWM8j9sPmZuZfo\n"
          + "G0d/SMmu/ThIwRj4gHeydhUPFO4rcvsN3pPoAs+EiogcZJ3aJnXINhjVjYRSzJUI\n"
          + "yMJNUym995NJIMwZXd4hnTk=";

  static final String CLIENT_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDGTCCAgECFDikab6UgfPp1VYHdg5Ugl/dUe7pMA0GCSqGSIb3DQEBCwUAMEkx\n"
          + "CzAJBgNVBAYTAlVTMQswCQYDVQQIDAJVUzEUMBIGA1UECgwLR29vZ2xlLCBJbmMx\n"
          + "FzAVBgNVBAMMDkdvb2dsZSBBbGxveURCMB4XDTI0MDIwMjAyMjE1NFoXDTM0MDEz\n"
          + "MDAyMjE1NFowSTELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAlVTMRQwEgYDVQQKDAtH\n"
          + "b29nbGUsIEluYzEXMBUGA1UEAwwOR29vZ2xlIEFsbG95REIwggEiMA0GCSqGSIb3\n"
          + "DQEBAQUAA4IBDwAwggEKAoIBAQCw7p8bds6/PIIdcHQX7PM9wYdb3m7hZEaf1LTJ\n"
          + "pz0oAAyhnf0fHp32d9cU8msdcdPovF9GqfRzUvLm5b7Hp7PHBCa/DIMOsHDW51Y4\n"
          + "9mtyEX+Ss3ex4Uyexem/uqE3rw5Gj3UvZv65JAvqZZ3oxA0n91cgPl0u6zmAYgEb\n"
          + "DtP69JVnhSSYBqwNArJ5NEmTrwsBtg2dshED/BynPhaTRJyUA3x5F4fhh408LjCU\n"
          + "pjoHV2lkYxqstJaYdd8jv6PFySJRe/86PGe5XKLqN7ftJQl18VtJz5RbLgPzfsM2\n"
          + "KFfvDw94WjANUg1mja8LeU19TGNrA9l6O5xUm/4UvSWcVdqLAgMBAAEwDQYJKoZI\n"
          + "hvcNAQELBQADggEBAI5CljrOCGYIspbIqEjgL2A79js17W5+psLcrL8v0FEPygih\n"
          + "T3wScddS82JSiGTlOiokCyJma+fJmp8XIG6PVgKIvMSWkZ76HHXRpwJySMpqvnLf\n"
          + "CSjeNyLDy+glhsHY4tSoiZJD09PYg7csIB3Ib/7oxlfUARePfRQl7JkpsPtWlyOv\n"
          + "JoW8wUWQ4J761+kUfVFpBiiTkJNn4Izji5z+s1AT7PS08fjnKpm26ZgjTKP5mPbF\n"
          + "gAdiIVhwR2vFLB8ofS06Vx+7Iwx+zhYp7I7zOMZW5bcrFRuVkV0cIXNeJ80vgKMa\n"
          + "CQfzf3+G48SqMG+ruXwDRt74DSr8dLkDaQmsUNE="
          + "-----END CERTIFICATE-----";

  static final String SIGNING_CA_PRIVATE_KEY =
      "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCdF2d33olkhdyg\n"
          + "fVsBcWzIpsS8C2+dn0VyWQqCsU8qTpiF4SqvQIAnQmvkDlyTTMJjoSmwEfGaYFkx\n"
          + "7NO0neYtYgbdrmoX+GlWDA0/qgPiUd6jm3lTW46pT9XJbmoIxiAndY+G/fltAfbx\n"
          + "E4W/K1YRNaWLgnWrwBWx1n7LmxFahbX/fs2vROJwt8xLztpONovv9hOzheApkaIb\n"
          + "v/Or1BhxQgqtVl0T9NiU/Sxn4bJ2Eodl+9S9ivjXP6N5dqL8NolFmscmcCgUB3zG\n"
          + "IS/y7Lsi/fvLByEXzOXDcWbUytFzFVYpf90/RX4sSu0wbNpNI3Dq2/lDxkTa5XAK\n"
          + "lzf6YvPHAgMBAAECggEAGC1ERlrgiFu1w0QntRwmIud6Zc/Yw3MszAF6ME0Q+FuU\n"
          + "9nMwujzSEbi+RCDAYgcxRQYbwAuy7xB0XyOqHXp5Ch5eciW9Zv0GBoaKT3RfbqiC\n"
          + "Ub8ZjW5iPBqbtjRLwMKaq5gDQ+0N7eyceykxr2SrCrECo8BV/d3tONaLOH4gI0SC\n"
          + "f1h1RHJREZVuNjMCMF3agT6SZfSo4sj7MQRWFzGB0h+zEyQeVmpgy+gDH8XIihkz\n"
          + "d421TZkVbAT7h6FdM/WyUyozzfCkpdsLkHBHXbimRkiBRgocK6ezpT+Y9RNx+iS2\n"
          + "zAbCGJPIYPe1O5krXnByKuh7kVjxN+7fL9qntpjj8QKBgQDNOOpIBBM6EHhRK74i\n"
          + "WKAdsGkybMoaf2BTT5Z4D0fomMNHFW8y1GDtXzP7yMvwSB7ywTD/KpS8Au+Ox47b\n"
          + "3Izy5kdpx3GNHC1txV2Qr64/K6wo0vOPAQCBRLAoxPBFxWLnQrQgs2N879NgzD/q\n"
          + "Q+8Wkafmde3p+pJVVTI6Z/k/yQKBgQDD9c6AfCgm9h4rZXn2lv30PWhs2WqkVj6X\n"
          + "T4ui7hKe3vq+nUMMcAmAxbvy5GK2wK0tOQ30EvkjlMqHlksumg01ZUKFZKJ5iZAQ\n"
          + "DFmrnVzbVzbUgOL2AKtl85otEKWvMmEQSci64TMSjWMyQYu6Q4jSbu4LQwYv290f\n"
          + "XDfzT8f/DwKBgCdHvZALN6LlhCIFPV9Vl1AXdAsyx3xK5SUNFr/kLN9YXgg9Eguo\n"
          + "muTUVB7dUCutoj7Uu4wbmlxiM3ggujda/59+dCzK//ECc6+PF1maRr/QnCM0PWoh\n"
          + "xvb3LsCe8V5paYTaP6ACtggz30Gn1pQAbMLlHj3+VIt0ao3qwLJtNEgBAoGARupc\n"
          + "uei0iy0ETAYkNhX8f3f9o26nBiPj8NQrn2HywSXGrlaxHf4poj1swADgrGxb/4Kn\n"
          + "Rw3atYKyLJE6UfoUD5++jBGYLs2WoggmJ1ICeao9QHgIKqihXnri764Xl6husftR\n"
          + "tjerSOp73IMV8ulfyW+1m8O8qHMzuZag9N+JbAcCgYEApwCxIcnPmv/rSDIWloU1\n"
          + "HYiBu2PWPgbaVWZH2UewrqUVRtkp+KnzlDmKF9lcqZaiavlmBgahlxB2x9KIhlpJ\n"
          + "IfNj1pMSWvF1tXXlkCoAWawU9s1kYtLZmvlgHmhXWhU9/PSGmZ2M3I5ljFjoYJN5\n"
          + "5EaHaBYfM6wGgUwOaxtc5HQ=";

  static final String SIGNING_CA_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDczCCAlugAwIBAgIUVBeC053gIZyEgtGVPhSNSQPnGvYwDQYJKoZIhvcNAQEL\n"
          + "BQAwSTELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAlVTMRQwEgYDVQQKDAtHb29nbGUs\n"
          + "IEluYzEXMBUGA1UEAwwOR29vZ2xlIEFsbG95REIwHhcNMjQwMjAyMDA0NTM0WhcN\n"
          + "MzQwMTMwMDA0NTM0WjBJMQswCQYDVQQGEwJVUzELMAkGA1UECAwCVVMxFDASBgNV\n"
          + "BAoMC0dvb2dsZSwgSW5jMRcwFQYDVQQDDA5Hb29nbGUgQWxsb3lEQjCCASIwDQYJ\n"
          + "KoZIhvcNAQEBBQADggEPADCCAQoCggEBAJ0XZ3feiWSF3KB9WwFxbMimxLwLb52f\n"
          + "RXJZCoKxTypOmIXhKq9AgCdCa+QOXJNMwmOhKbAR8ZpgWTHs07Sd5i1iBt2uahf4\n"
          + "aVYMDT+qA+JR3qObeVNbjqlP1cluagjGICd1j4b9+W0B9vEThb8rVhE1pYuCdavA\n"
          + "FbHWfsubEVqFtf9+za9E4nC3zEvO2k42i+/2E7OF4CmRohu/86vUGHFCCq1WXRP0\n"
          + "2JT9LGfhsnYSh2X71L2K+Nc/o3l2ovw2iUWaxyZwKBQHfMYhL/LsuyL9+8sHIRfM\n"
          + "5cNxZtTK0XMVVil/3T9FfixK7TBs2k0jcOrb+UPGRNrlcAqXN/pi88cCAwEAAaNT\n"
          + "MFEwHQYDVR0OBBYEFOPZckHWcDjT4yjxhbXrvDI6U3e4MB8GA1UdIwQYMBaAFOPZ\n"
          + "ckHWcDjT4yjxhbXrvDI6U3e4MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEL\n"
          + "BQADggEBADxe5BWMzM6bMff3Wf5FXPQkXvbtNFuQxA5zdH5r7UZY0Pj6phpZPjyo\n"
          + "IAtb02NqANFt7lZ/YwjD9KmfuNvvC4ENKapqZ9PDy3Pon4NkiZNBwGpa/G/QE9d0\n"
          + "b7imRH01fad/CT9X321oU52ybvEhKYu6VAFHpYPoMj5yEe8ib34XGChWWT/CvcTC\n"
          + "7p6Kha4d0ueqjeCBlMbr+GJgRPgfjc5tzPtzom9XVjDLGkSwYSCDo9TYuVghYqcc"
          + "Ll9Pt3ZnDnteA1W7AOiRKHdyyKZYy5UMVCaOA1Evqa6fA/c0QWIXRHQTwfXNFA59"
          + "R1BmNA/ePVsq6bzDThRc5qrr2hA41qg="
          + "-----END CERTIFICATE-----";

  static final String SERVER_CERT =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDGTCCAgECFE19gRBeLBNwPsrbQA/HMRQLXrbuMA0GCSqGSIb3DQEBCwUAMEkx\n"
          + "CzAJBgNVBAYTAlVTMQswCQYDVQQIDAJVUzEUMBIGA1UECgwLR29vZ2xlLCBJbmMx\n"
          + "FzAVBgNVBAMMDkdvb2dsZSBBbGxveURCMB4XDTI0MDIwMjAwNTAxMFoXDTM0MDEz\n"
          + "MDAwNTAxMFowSTELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAlVTMRQwEgYDVQQKDAtH\n"
          + "b29nbGUsIEluYzEXMBUGA1UEAwwOR29vZ2xlIEFsbG95REIwggEiMA0GCSqGSIb3\n"
          + "DQEBAQUAA4IBDwAwggEKAoIBAQDB9U/OCzEXHR39rFVZBZ899TgBSqqWTaurUPrb\n"
          + "tCwa6qDfRZZJjHm1hyN2MFT84m35t4lvPl6OGe5znCij9GftIJEbGgCNNbQJbsna\n"
          + "VWlU7ZpwdVUHaktJ19NYLKhsgirAeLOTBz20UE/0hZmj18KSBA8xojSb9TyRX0AW\n"
          + "tCcSsjLzvFjib8ccvZ1tAMkPb8gJaOMewc3Trx+3owrkDkqageT/LiislziYVhuE\n"
          + "f8P21ggVfXlZ275jtvaGJ/fgq7I+isDHcFeGnWaVq1iaGz1bhxsCmhZXMrhBSD0H\n"
          + "2vaGq30eugOGGEkK/eVgHSQGz7LxKzAfYIjab/O2g+Pr6ewHAgMBAAEwDQYJKoZI\n"
          + "hvcNAQELBQADggEBAJfjDyDs42FrCGyiHGtbKynG1CcFh+be7LelYj26hf691veB\n"
          + "SfBNFXdw5iM23Y4jRC7lWgQWVTbTd/GrFQsE+3oquYGVIWAKYILyQsF/qMm9Bl9j\n"
          + "jftXSGZ3wbl8fph6uACjmaiaUEOVr3RTRM+RG5VxYdkHpoLxS3Xj4DrNAsjinZOF\n"
          + "VvefeqZ7CBpuxa8dFe3LTUugm52BhDxDnW1HrvCZ8WUYtqyUu2xM/vONeL/IILJ0\n"
          + "rjQ2Gj6x5TMjjFlSnR4woLnnosre8+bV9A8HyOTe439CKGEVHrKKNn9J6gWj5APk\n"
          + "zqy7ZpLK1u+M9+O9GaHdsJ90GiMAFnUJ4ncu4jI="
          + "-----END CERTIFICATE-----";

  static final String SERVER_CERT_PRIVATE_KEY =
      "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDB9U/OCzEXHR39\n"
          + "rFVZBZ899TgBSqqWTaurUPrbtCwa6qDfRZZJjHm1hyN2MFT84m35t4lvPl6OGe5z\n"
          + "nCij9GftIJEbGgCNNbQJbsnaVWlU7ZpwdVUHaktJ19NYLKhsgirAeLOTBz20UE/0\n"
          + "hZmj18KSBA8xojSb9TyRX0AWtCcSsjLzvFjib8ccvZ1tAMkPb8gJaOMewc3Trx+3\n"
          + "owrkDkqageT/LiislziYVhuEf8P21ggVfXlZ275jtvaGJ/fgq7I+isDHcFeGnWaV\n"
          + "q1iaGz1bhxsCmhZXMrhBSD0H2vaGq30eugOGGEkK/eVgHSQGz7LxKzAfYIjab/O2\n"
          + "g+Pr6ewHAgMBAAECggEAVlLH4fw5LQBYiL5affRymzC4bFq+8YZAEU9JVt8pghFK\n"
          + "6BQgfzt2L8Slk8SPDr34FFwLXudzTetcpTerHs14M6F684TvGen85vXYAMRizNmz\n"
          + "ErolzdcRCxxzg5rcmu6T+HW/9oAShl34N+v+JV2xyyrjWEPJBmBvRIQQEgq8GSVP\n"
          + "E1ls0ngRsu0JRgA5+tYGpQfm4khYqAW3/VjLUigiPiVzr7c9X4VTGlT2tEWSYxY/\n"
          + "ddOkCbRMkMjr/fmFwbd+zvkwnSBZc+8GkitfL1P2udkOlN+JrdLV45fcmvlGmmKY\n"
          + "Bs0fIpKNrlYoIj7EssKqbUrs108GTmfNaQLbj3LFgQKBgQD2IKbXlETdj3F1NlkL\n"
          + "omitPjat8+y0iW21kpc0rx1EO+BlNlRhbq1c2zgI+Rqc5MhmZKqGE3rI6hdLVpLi\n"
          + "MAS43u8zybusSC2DSpBm+IQoEhYjLlvpPKRbC0DuZMDYHFIXfOqH28XW5D2zodsm\n"
          + "qf95fv4BU5VOYTLREwcJ/qKwewKBgQDJvPZUH6SgbtXzPttq5cuw0GebK0KtTDxb\n"
          + "70FndBqNi+vo63DDiAkmlv2f39MwiyqthWeTqrYkwTGZ/j5ocZ/OxF1YRnBc/Oxa\n"
          + "7Ww9XkwulUY0OzFo8vxtTcfVAG9YPVTh1AnZVVkvKNLQfk1xn5eoGoCl/+3Lau6I\n"
          + "TR9JRBPK5QKBgQCsD4FzXaSS2u9vCHJRjtTsn3xsOQS15Qj8ESGBZBXqmI0zVDrC\n"
          + "7jNloZ7XrwUqv0lVQ3RuTHnesL9eHISMeRMkBj1kj9eSBddDXEH8qikBNjuhlowM\n"
          + "Tid7ui9HOMoTiiDdaKcwGLSCmIaF6FWi/t6pGd0KIltTMuHqhQm7s/YZtwKBgQCB\n"
          + "V1ePQ+JmwekGVYyUEtTvfg6PG8NaHICuaM1EKNpFWipcYWcg1f0X8sKVWAmtG+y2\n"
          + "58sqj87L7dmBY9JbYE4XYSp/yFmOJNLc0VAOYIDzdN1X64OYSAGziqTOWcMJjfj3\n"
          + "+Nx/rQrLA5918SRx7uJq8uL8iwPI4bwhQ2EFtlICSQKBgElS6Vuf5wmsY46CoS8v\n"
          + "C4sj0rH2vvd+0SJQ3BLqZ7nYKFWnK51YmN3Ov9SQwtabjV+Ld4oa6y2c+Op0L40M\n"
          + "kbYhuV4j4anTysFdKpiD6hEnYFkOM2eE7dUK9w7qagZNOBKbLpqZFXnBCTOVmaj+\n"
          + "zzzMeuzI2oqt8Eo5FiHWVNah";
}
