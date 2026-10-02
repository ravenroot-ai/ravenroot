// Generated from the reviewed Java resolver contracts. Edit deliberately; never infer this catalog from examples.
import type { ValueSchema } from "./types.js";

export const CONTRACT_SCHEMA_VERSION = 1 as const;
export const EXPLICIT_CONTRACT_SCHEMAS = {
  "bundle.service-grant": {
    "kind": "object",
    "optional": [
      "origins",
      "httpMethods",
      "requestHeaders",
      "responseHeaders",
      "webSocketSubprotocols",
      "credentialBindings",
      "awsSigV4Bindings",
      "credentialReferences",
      "limits"
    ],
    "properties": {
      "capabilities": {
        "kind": "array",
        "items": {
          "kind": "string",
          "allowed": [
            "credential-resolution",
            "outbound-http",
            "outbound-websocket",
            "tool-authorization",
            "agent-resources"
          ]
        },
        "minimumItems": 1,
        "maximumItems": 5,
        "unique": true
      },
      "origins": {
        "kind": "array",
        "items": {
          "kind": "object",
          "properties": {
            "scheme": {
              "kind": "string",
              "allowed": [
                "http",
                "https",
                "ws",
                "wss"
              ]
            },
            "host": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 253,
              "pattern": "[^\\s/:]+"
            },
            "port": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 65535
            }
          }
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "httpMethods": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 16,
          "pattern": "[A-Z]+"
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "requestHeaders": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "responseHeaders": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "webSocketSubprotocols": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "credentialBindings": {
        "kind": "array",
        "items": {
          "kind": "object",
          "properties": {
            "bindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "origin": {
              "kind": "object",
              "properties": {
                "scheme": {
                  "kind": "string",
                  "allowed": [
                    "http",
                    "https",
                    "ws",
                    "wss"
                  ]
                },
                "host": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 253,
                  "pattern": "[^\\s/:]+"
                },
                "port": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 65535
                }
              }
            },
            "headerName": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "prefix": {
              "kind": "string",
              "minimumLength": 0,
              "maximumLength": 256
            }
          }
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "awsSigV4Bindings": {
        "kind": "array",
        "items": {
          "kind": "object",
          "properties": {
            "bindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "origin": {
              "kind": "object",
              "properties": {
                "scheme": {
                  "kind": "string",
                  "allowed": [
                    "http",
                    "https",
                    "ws",
                    "wss"
                  ]
                },
                "host": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 253,
                  "pattern": "[^\\s/:]+"
                },
                "port": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 65535
                }
              }
            },
            "credentialReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "region": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 63
            },
            "service": {
              "kind": "string",
              "allowed": [
                "s3"
              ]
            }
          }
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "credentialReferences": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128
        },
        "minimumItems": 0,
        "maximumItems": 64,
        "unique": true
      },
      "limits": {
        "kind": "object",
        "optional": [
          "maxRequestBytes",
          "maxResponseBytes",
          "maxWebSocketMessageBytes",
          "maxWebSocketFragments",
          "maxQueuedWebSocketSends",
          "maxConcurrentOperations",
          "maxConcurrentPerTenant",
          "maxDecompressionRatio",
          "maxDeadlineMs",
          "maxWebSocketLifetimeMs",
          "maxWebSocketIdleMs"
        ],
        "properties": {
          "maxRequestBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxResponseBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxWebSocketMessageBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxWebSocketFragments": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxQueuedWebSocketSends": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxConcurrentOperations": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxConcurrentPerTenant": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxDecompressionRatio": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1000
          },
          "maxDeadlineMs": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxWebSocketLifetimeMs": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          },
          "maxWebSocketIdleMs": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2147483647
          }
        }
      }
    }
  },
  "ai.llm-profile": {
    "kind": "object",
    "properties": {
      "endpoint": {
        "kind": "string",
        "format": "uri",
        "minimumLength": 1,
        "maximumLength": 2048,
        "schemes": [
          "http",
          "https"
        ],
        "requireHost": true,
        "allowFragment": false
      },
      "model": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "credentialBindingId": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "credentialReference": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "timeoutMs": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 600000
      },
      "maxRequestBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 8388608
      },
      "maxResponseBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 8388608
      },
      "maxConcurrency": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 256
      },
      "systemPreamble": {
        "kind": "string",
        "minimumLength": 0,
        "maximumLength": 8192
      }
    },
    "optional": [
      "credentialBindingId",
      "credentialReference",
      "timeoutMs",
      "maxRequestBytes",
      "maxResponseBytes",
      "maxConcurrency",
      "systemPreamble"
    ]
  },
  "ai.mcp-profile": {
    "kind": "object",
    "properties": {
      "endpoint": {
        "kind": "string",
        "format": "uri",
        "minimumLength": 1,
        "maximumLength": 2048,
        "schemes": [
          "http",
          "https"
        ],
        "requireHost": true,
        "allowFragment": false
      },
      "credentialBindingId": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "credentialReference": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "timeoutMs": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 600000
      },
      "maxRequestBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16777216
      },
      "maxResponseBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16777216
      },
      "maxConcurrency": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 256
      },
      "maxDiscoveredTools": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 1024
      },
      "allowedTools": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128,
          "pattern": "[A-Za-z0-9_-]{1,64}"
        },
        "minimumItems": 1,
        "maximumItems": 128,
        "unique": true
      }
    },
    "optional": [
      "credentialBindingId",
      "credentialReference",
      "timeoutMs",
      "maxRequestBytes",
      "maxResponseBytes",
      "maxConcurrency",
      "maxDiscoveredTools"
    ]
  },
  "git-workspace.profile": {
    "kind": "object",
    "properties": {
      "root": {
        "kind": "string",
        "format": "absolute-path",
        "minimumLength": 1,
        "maximumLength": 4096
      },
      "remote": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 2048
      },
      "baseRef": {
        "kind": "string",
        "minimumLength": 12,
        "maximumLength": 255,
        "pattern": "refs/heads/[^\\s~^:?*\\\\\\[\\]]+"
      },
      "issueRefPrefix": {
        "kind": "string",
        "minimumLength": 12,
        "maximumLength": 255,
        "pattern": "refs/heads/[^\\s~^:?*\\\\\\[\\]]+"
      },
      "gitExecutable": {
        "kind": "string",
        "format": "absolute-path",
        "minimumLength": 1,
        "maximumLength": 4096
      },
      "processShellExecutable": {
        "kind": "string",
        "format": "absolute-path",
        "minimumLength": 1,
        "maximumLength": 4096
      },
      "objectFormat": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 6,
        "allowed": [
          "sha1",
          "sha256"
        ]
      },
      "deadlineMs": {
        "kind": "integer",
        "minimum": 100,
        "maximum": 300000
      },
      "maxConcurrency": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 64
      },
      "maxOutputBytes": {
        "kind": "integer",
        "minimum": 1024,
        "maximum": 1048576
      },
      "historyScanLimit": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 10000
      },
      "credentialRef": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "credentialUsername": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      }
    },
    "optional": [
      "credentialRef",
      "credentialUsername"
    ]
  },
  "jdbc.profile": {
    "kind": "object",
    "properties": {
      "driverId": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 64,
        "pattern": "[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62}[A-Za-z0-9])?"
      },
      "driverClass": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 255,
        "pattern": "[A-Za-z_$][A-Za-z0-9_$.]{0,254}"
      },
      "driverSha256": {
        "kind": "string",
        "format": "sha256",
        "minimumLength": 64,
        "maximumLength": 64
      },
      "url": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 2048,
        "pattern": "jdbc:[^\\u0000-\\u001f\\u007f]+"
      },
      "username": {
        "kind": "string",
        "minimumLength": 0,
        "maximumLength": 256
      },
      "credentialRef": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "schema": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "isolation": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 16,
        "allowed": [
          "READ_COMMITTED",
          "REPEATABLE_READ",
          "SERIALIZABLE"
        ]
      },
      "deadlineMs": {
        "kind": "integer",
        "minimum": 100,
        "maximum": 30000
      },
      "maxConcurrency": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16
      },
      "maxParameters": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 256
      },
      "maxParameterBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 1048576
      },
      "maxRows": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 10000
      },
      "maxColumns": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 256
      },
      "maxCellBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 1048576
      },
      "maxTotalBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16777216
      },
      "maxGeneratedKeyRows": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 1000
      },
      "statements": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "kind": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 6
            },
            "sql": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 65536
            },
            "generatedKeys": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 128,
                "pattern": "[A-Za-z_][A-Za-z0-9_.-]{0,127}"
              },
              "minimumItems": 0,
              "maximumItems": 32,
              "unique": true
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 128,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,159}"
      }
    },
    "optional": [
      "schema"
    ]
  },
  "object-storage.profile": {
    "kind": "object",
    "properties": {
      "origin": {
        "kind": "string",
        "format": "uri",
        "minimumLength": 1,
        "maximumLength": 512,
        "schemes": [
          "https"
        ],
        "requireHost": true,
        "allowFragment": false
      },
      "region": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 63,
        "pattern": "[A-Za-z0-9][A-Za-z0-9-]*"
      },
      "bucket": {
        "kind": "string",
        "minimumLength": 3,
        "maximumLength": 63,
        "pattern": "[a-z0-9][a-z0-9.-]*[a-z0-9]"
      },
      "keyPrefix": {
        "kind": "string",
        "minimumLength": 0,
        "maximumLength": 1024
      },
      "addressingStyle": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 32,
        "allowed": [
          "path",
          "virtual-hosted"
        ]
      },
      "signingBindingId": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256
      },
      "operations": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 16,
          "allowed": [
            "get",
            "put",
            "list",
            "delete",
            "delete-version"
          ]
        },
        "minimumItems": 1,
        "maximumItems": 5,
        "unique": true
      },
      "contentTypes": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128
        },
        "minimumItems": 0,
        "maximumItems": 32,
        "unique": true
      },
      "allowIfMatch": {
        "kind": "boolean"
      },
      "allowIfNoneMatch": {
        "kind": "boolean"
      },
      "maxObjectBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16777216
      },
      "timeoutMs": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 300000
      },
      "maxConcurrency": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 256
      },
      "maxRequestsPerSecond": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 10000
      }
    }
  },
  "openapi-client.profile": {
    "kind": "object",
    "properties": {
      "origin": {
        "kind": "string",
        "format": "uri",
        "minimumLength": 1,
        "maximumLength": 512,
        "schemes": [
          "https"
        ],
        "requireHost": true,
        "allowFragment": false
      },
      "specBase64": {
        "kind": "string",
        "format": "base64",
        "minimumLength": 4,
        "maximumLength": 2796204
      },
      "specSha256": {
        "kind": "string",
        "minimumLength": 64,
        "maximumLength": 64,
        "pattern": "[0-9A-Fa-f]{64}"
      },
      "operations": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128,
          "pattern": "[A-Za-z0-9._-]{1,128}"
        },
        "minimumItems": 1,
        "maximumItems": 128,
        "unique": true
      },
      "fixedHeaders": {
        "kind": "map",
        "values": {
          "kind": "array",
          "items": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 512
          },
          "maximumItems": 256,
          "unique": true
        },
        "maximumEntries": 32,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,159}"
      },
      "inputHeaders": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128
        },
        "minimumItems": 0,
        "maximumItems": 32,
        "unique": true
      },
      "responseHeaders": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128
        },
        "minimumItems": 0,
        "maximumItems": 32,
        "unique": true
      },
      "credentialBindingId": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256,
        "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"
      },
      "credentialReference": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 256,
        "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"
      },
      "maxRequestBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16777216
      },
      "maxResponseBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16777216
      },
      "timeoutMs": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 300000
      },
      "maxConcurrency": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 256
      }
    }
  },
  "openapi-server.config": {
    "kind": "object",
    "properties": {
      "authority": {
        "kind": "object",
        "properties": {
          "listenerId": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "pathPrefix": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 320
          },
          "requiredScopes": {
            "kind": "array",
            "items": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160
            },
            "minimumItems": 0,
            "maximumItems": 32,
            "unique": true
          },
          "maxRoutes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxConcurrentRequests": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1024
          },
          "maxRequestBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "maxResponseBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "requestTimeoutMs": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 300000
          }
        }
      },
      "projection": {
        "kind": "object",
        "properties": {
          "allowedHeaders": {
            "kind": "array",
            "items": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160
            },
            "minimumItems": 0,
            "maximumItems": 32,
            "unique": true
          },
          "idempotencyHeader": {
            "kind": "string",
            "minimumLength": 0,
            "maximumLength": 64
          },
          "maxRelativePathBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxQueryParameters": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxQueryBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16384
          },
          "maxHeaderCount": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 32
          },
          "maxHeaderBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxHeaderValueBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2048
          }
        }
      },
      "profiles": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "routeBase": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "specBase64": {
              "kind": "string",
              "format": "base64",
              "minimumLength": 4
            },
            "specSha256": {
              "kind": "string",
              "minimumLength": 64,
              "maximumLength": 64,
              "pattern": "[0-9A-Fa-f]{64}"
            },
            "operations": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 160,
                "pattern": "[A-Za-z0-9._-]{1,128}"
              },
              "minimumItems": 1,
              "maximumItems": 128,
              "unique": true
            },
            "principalTypes": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 8,
                "allowed": [
                  "USER",
                  "WORKLOAD"
                ]
              },
              "minimumItems": 1,
              "maximumItems": 2,
              "unique": true
            },
            "idempotencyHeader": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 64
            },
            "maxRequestBytes": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 16777216
            },
            "maxIdempotencyBytes": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 1024
            },
            "deadlineMs": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 300000
            },
            "maxConcurrency": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 256
            },
            "targetNode": {
              "kind": "nullable",
              "value": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 160,
                "pattern": "[A-Za-z0-9][A-Za-z0-9._:-]{0,159}"
              }
            }
          },
          "optional": [
            "targetNode"
          ]
        },
        "minimumEntries": 1,
        "maximumEntries": 256,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
      }
    }
  },
  "websocket.profile": {
    "kind": "object",
    "properties": {
      "destination": {
        "kind": "string",
        "format": "uri",
        "minimumLength": 1,
        "maximumLength": 2048,
        "schemes": [
          "wss"
        ],
        "requireHost": true,
        "allowFragment": false
      },
      "headers": {
        "kind": "map",
        "values": {
          "kind": "array",
          "items": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 512
          },
          "maximumItems": 8,
          "unique": true,
          "minimumItems": 1
        },
        "maximumEntries": 32,
        "keyPattern": "[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}"
      },
      "subprotocols": {
        "kind": "array",
        "items": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 128,
          "pattern": "[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}"
        },
        "minimumItems": 0,
        "maximumItems": 16,
        "unique": true
      },
      "credentialBindingId": {
        "kind": "nullable",
        "value": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 256,
          "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"
        }
      },
      "credentialReference": {
        "kind": "nullable",
        "value": {
          "kind": "string",
          "minimumLength": 1,
          "maximumLength": 256,
          "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"
        }
      },
      "maximumMessageBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 16777216
      },
      "maximumFragments": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 1024
      },
      "timeoutMs": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 300000
      },
      "reconnectBackoffMs": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 300000
      },
      "maxConcurrency": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 256
      },
      "maxBufferedEvents": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 65536
      }
    }
  },
  "discord.config": {
    "kind": "object",
    "properties": {
      "authority": {
        "kind": "object",
        "properties": {
          "listenerId": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "pathPrefix": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "requiredScopes": {
            "kind": "array",
            "items": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "minimumItems": 0,
            "maximumItems": 32,
            "unique": true
          },
          "maxRoutes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxConcurrentRequests": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1024
          },
          "maxRequestBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "maxResponseBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "requestTimeoutMs": {
            "kind": "integer",
            "minimum": 100,
            "maximum": 2800
          }
        }
      },
      "projection": {
        "kind": "object",
        "properties": {
          "maxRelativePathBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxQueryParameters": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxQueryBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16384
          },
          "maxHeaderCount": {
            "kind": "integer",
            "minimum": 2,
            "maximum": 32
          },
          "maxHeaderBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxHeaderValueBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2048
          }
        }
      },
      "store": {
        "kind": "object",
        "properties": {
          "path": {
            "kind": "string",
            "format": "absolute-path",
            "minimumLength": 1,
            "maximumLength": 4096
          },
          "maxDeliveries": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1000000
          },
          "retentionHours": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8760
          }
        }
      },
      "profiles": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "tenantId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "[A-Za-z0-9][A-Za-z0-9._:-]{0,159}"
            },
            "apiOrigin": {
              "kind": "string",
              "format": "uri",
              "minimumLength": 1,
              "maximumLength": 2048,
              "schemes": [
                "http",
                "https"
              ],
              "requireHost": true,
              "allowFragment": false
            },
            "applicationId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 20,
              "pattern": "[0-9]{1,20}"
            },
            "publicKeyHex": {
              "kind": "string",
              "minimumLength": 64,
              "maximumLength": 64,
              "pattern": "[0-9A-Fa-f]{64}"
            },
            "guilds": {
              "kind": "map",
              "values": {
                "kind": "array",
                "items": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 512
                },
                "maximumItems": 256,
                "unique": true
              },
              "maximumEntries": 128,
              "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,159}"
            },
            "commands": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 32,
                "pattern": "[a-z0-9_-]{1,32}"
              },
              "minimumItems": 1,
              "maximumItems": 100,
              "unique": true
            },
            "credentialBindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256,
              "pattern": "[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"
            },
            "credentialReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256,
              "pattern": "[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"
            },
            "route": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "/[A-Za-z0-9._~/-]{1,159}"
            },
            "limits": {
              "kind": "object",
              "properties": {
                "requestTimeoutMs": {
                  "kind": "integer",
                  "minimum": 100,
                  "maximum": 2800
                },
                "maxRequestBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxResponseBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxContentChars": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 2000
                },
                "maxAttachmentBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 8388608
                },
                "maxAttachments": {
                  "kind": "integer",
                  "minimum": 0,
                  "maximum": 10
                },
                "maxConcurrency": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 64
                },
                "maxPerSecond": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 50
                },
                "retries": {
                  "kind": "integer",
                  "minimum": 0,
                  "maximum": 3
                },
                "signatureMaxAgeSeconds": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 300
                },
                "futureSkewSeconds": {
                  "kind": "integer",
                  "minimum": 0,
                  "maximum": 60
                }
              }
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 256,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
      }
    }
  },
  "github.config": {
    "kind": "object",
    "properties": {
      "authority": {
        "kind": "object",
        "properties": {
          "listenerId": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "pathPrefix": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "requiredScopes": {
            "kind": "array",
            "items": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "minimumItems": 0,
            "maximumItems": 32,
            "unique": true
          },
          "maxRoutes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxConcurrentRequests": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1024
          },
          "maxRequestBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "maxResponseBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "requestTimeoutMs": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 300000
          }
        }
      },
      "projection": {
        "kind": "object",
        "properties": {
          "maxRelativePathBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxQueryParameters": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxQueryBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16384
          },
          "maxHeaderCount": {
            "kind": "integer",
            "minimum": 3,
            "maximum": 32
          },
          "maxHeaderBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxHeaderValueBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2048
          }
        }
      },
      "store": {
        "kind": "object",
        "properties": {
          "path": {
            "kind": "string",
            "format": "absolute-path",
            "minimumLength": 1,
            "maximumLength": 4096
          },
          "maxOperations": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1000000
          },
          "retentionHours": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8760
          },
          "leaseMs": {
            "kind": "integer",
            "minimum": 1000,
            "maximum": 300000
          }
        }
      },
      "profiles": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "tenantId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"
            },
            "apiOrigin": {
              "kind": "string",
              "format": "uri",
              "minimumLength": 1,
              "maximumLength": 512,
              "schemes": [
                "https"
              ],
              "requireHost": true,
              "allowFragment": false
            },
            "owner": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 100
            },
            "repository": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 100
            },
            "repositoryId": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 9007199254740991
            },
            "installationId": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 9007199254740991
            },
            "reviewerLogin": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 100
            },
            "credentialBindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "credentialReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "webhookSecretReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "route": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160
            },
            "events": {
              "kind": "map",
              "values": {
                "kind": "array",
                "items": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 512
                },
                "maximumItems": 256,
                "unique": true
              },
              "maximumEntries": 128,
              "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,159}"
            },
            "project": {
              "kind": "object",
              "properties": {
                "projectId": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 128
                },
                "statusFieldId": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 128
                },
                "attemptsFieldId": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 128
                },
                "generationFieldId": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 128
                },
                "statusOptions": {
                  "kind": "map",
                  "values": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 128
                  },
                  "maximumEntries": 64,
                  "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,159}"
                },
                "allowedTransitions": {
                  "kind": "array",
                  "items": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 130
                  },
                  "minimumItems": 1,
                  "maximumItems": 128,
                  "unique": true
                },
                "claimTransition": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 130
                }
              }
            },
            "workflowIds": {
              "kind": "array",
              "items": {
                "kind": "integer",
                "minimum": 1,
                "maximum": 9007199254740991
              },
              "minimumItems": 1,
              "maximumItems": 64,
              "unique": true
            },
            "release": {
              "kind": "object",
              "properties": {
                "branch": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 160
                },
                "versionPath": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 256
                },
                "fragmentsPath": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 256
                },
                "allowedKinds": {
                  "kind": "array",
                  "items": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 16
                  },
                  "minimumItems": 1,
                  "maximumItems": 4,
                  "unique": true
                },
                "maxFiles": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1000
                }
              }
            },
            "limits": {
              "kind": "object",
              "properties": {
                "timeoutMs": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 300000
                },
                "maxRequestBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 2097152
                },
                "maxResponseBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 2097152
                },
                "maxConcurrency": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 128
                },
                "maxPolls": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1000
                },
                "pollIntervalMs": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 60000
                }
              }
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 256,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
      }
    }
  },
  "matrix.config": {
    "kind": "object",
    "properties": {
      "store": {
        "kind": "object",
        "properties": {
          "path": {
            "kind": "string",
            "format": "absolute-path",
            "minimumLength": 1,
            "maximumLength": 4096
          },
          "maxDeliveries": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1000000
          },
          "retentionHours": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8760
          },
          "maxSources": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 10000
          }
        }
      },
      "profiles": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "tenantId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}"
            },
            "homeserverOrigin": {
              "kind": "string",
              "format": "uri",
              "minimumLength": 1,
              "maximumLength": 2048,
              "schemes": [
                "http",
                "https"
              ],
              "requireHost": true,
              "allowFragment": false
            },
            "userId": {
              "kind": "string",
              "minimumLength": 4,
              "maximumLength": 255,
              "pattern": "@[^:\\u0000-\\u0020\\u007f]+:[^\\u0000-\\u0020\\u007f]+"
            },
            "rooms": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 4,
                "maximumLength": 255,
                "pattern": "![^:\\u0000-\\u0020\\u007f]+:[^\\u0000-\\u0020\\u007f]+"
              },
              "minimumItems": 1,
              "maximumItems": 256,
              "unique": true
            },
            "eventTypes": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 128,
                "pattern": "[a-z0-9][a-z0-9._-]{0,127}"
              },
              "minimumItems": 1,
              "maximumItems": 64,
              "unique": true
            },
            "credentialBindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256,
              "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"
            },
            "credentialReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256,
              "pattern": "[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"
            },
            "initialSyncMode": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 32,
              "allowed": [
                "skip",
                "deliver-bounded"
              ]
            },
            "initialSince": {
              "kind": "string",
              "minimumLength": 0,
              "maximumLength": 2048
            },
            "limits": {
              "kind": "object",
              "properties": {
                "requestTimeoutMs": {
                  "kind": "integer",
                  "minimum": 1000,
                  "maximum": 60000
                },
                "maxRequestBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxResponseBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 8388608
                },
                "maxTextChars": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 65535
                },
                "maxConcurrency": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 64
                },
                "maxPerSecond": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 50
                },
                "pollTimeoutMs": {
                  "kind": "integer",
                  "minimum": 0,
                  "maximum": 30000
                },
                "retryBackoffMs": {
                  "kind": "integer",
                  "minimum": 100,
                  "maximum": 60000
                },
                "maxEventsPerSync": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1000
                }
              }
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 512,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
      }
    }
  },
  "mattermost.config": {
    "kind": "object",
    "properties": {
      "authority": {
        "kind": "object",
        "properties": {
          "listenerId": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "pathPrefix": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "requiredScopes": {
            "kind": "array",
            "items": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "minimumItems": 0,
            "maximumItems": 32,
            "unique": true
          },
          "maxRoutes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxConcurrentRequests": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1024
          },
          "maxRequestBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "maxResponseBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "requestTimeoutMs": {
            "kind": "integer",
            "minimum": 100,
            "maximum": 2800
          }
        }
      },
      "projection": {
        "kind": "object",
        "properties": {
          "maxRelativePathBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxQueryParameters": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxQueryBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16384
          },
          "maxHeaderCount": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 32
          },
          "maxHeaderBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxHeaderValueBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2048
          }
        }
      },
      "store": {
        "kind": "object",
        "properties": {
          "path": {
            "kind": "string",
            "format": "absolute-path",
            "minimumLength": 1,
            "maximumLength": 4096
          },
          "maxDeliveries": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1000000
          },
          "retentionHours": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8760
          }
        }
      },
      "profiles": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "tenantId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160
            },
            "origin": {
              "kind": "string",
              "format": "uri",
              "minimumLength": 1,
              "maximumLength": 2048,
              "schemes": [
                "http",
                "https"
              ],
              "requireHost": true,
              "allowFragment": false
            },
            "teamId": {
              "kind": "string",
              "minimumLength": 26,
              "maximumLength": 32,
              "pattern": "[a-z0-9]{26}"
            },
            "publicChannels": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 32,
                "pattern": "[a-z0-9]{26}"
              },
              "minimumItems": 1,
              "maximumItems": 256,
              "unique": true
            },
            "credentialBindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "credentialReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "webhookTokenReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "outgoingWebhookRoute": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "/[A-Za-z0-9._~/-]{1,159}"
            },
            "limits": {
              "kind": "object",
              "properties": {
                "maxTextChars": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 16383
                },
                "maxRequestBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxResponseBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxConcurrency": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 64
                },
                "maxPerSecond": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 100
                },
                "requestTimeoutMs": {
                  "kind": "integer",
                  "minimum": 100,
                  "maximum": 2800
                },
                "retries": {
                  "kind": "integer",
                  "minimum": 0,
                  "maximum": 3
                }
              }
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 256,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
      }
    }
  },
  "slack.config": {
    "kind": "object",
    "properties": {
      "authority": {
        "kind": "object",
        "properties": {
          "listenerId": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "pathPrefix": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "requiredScopes": {
            "kind": "array",
            "items": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "minimumItems": 0,
            "maximumItems": 32,
            "unique": true
          },
          "maxRoutes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxConcurrentRequests": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1024
          },
          "maxRequestBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "maxResponseBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "requestTimeoutMs": {
            "kind": "integer",
            "minimum": 100,
            "maximum": 2800
          }
        }
      },
      "projection": {
        "kind": "object",
        "properties": {
          "maxRelativePathBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxQueryParameters": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxQueryBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16384
          },
          "maxHeaderCount": {
            "kind": "integer",
            "minimum": 5,
            "maximum": 32
          },
          "maxHeaderBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxHeaderValueBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2048
          }
        }
      },
      "store": {
        "kind": "object",
        "properties": {
          "path": {
            "kind": "string",
            "format": "absolute-path",
            "minimumLength": 1,
            "maximumLength": 4096
          },
          "maxDeliveries": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1000000
          },
          "retentionHours": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8760
          }
        }
      },
      "profiles": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "tenantId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160
            },
            "apiOrigin": {
              "kind": "string",
              "format": "uri",
              "minimumLength": 1,
              "maximumLength": 2048,
              "schemes": [
                "http",
                "https"
              ],
              "requireHost": true,
              "allowFragment": false
            },
            "teamId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 32
            },
            "applicationId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 32
            },
            "credentialBindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "credentialReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "signingSecretReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "eventsRoute": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "/[A-Za-z0-9._~/-]{1,159}"
            },
            "commandsRoute": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "/[A-Za-z0-9._~/-]{1,159}"
            },
            "channels": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 32
              },
              "minimumItems": 1,
              "maximumItems": 256,
              "unique": true
            },
            "eventTypes": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 80
              },
              "minimumItems": 1,
              "maximumItems": 128,
              "unique": true
            },
            "commands": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 33
              },
              "minimumItems": 1,
              "maximumItems": 100,
              "unique": true
            },
            "scopes": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 80
              },
              "minimumItems": 1,
              "maximumItems": 128,
              "unique": true
            },
            "limits": {
              "kind": "object",
              "properties": {
                "requestTimeoutMs": {
                  "kind": "integer",
                  "minimum": 100,
                  "maximum": 2800
                },
                "maxRequestBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxResponseBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxTextChars": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 4000
                },
                "maxConcurrency": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 64
                },
                "maxPerSecond": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 50
                },
                "retries": {
                  "kind": "integer",
                  "minimum": 0,
                  "maximum": 3
                },
                "signatureMaxAgeSeconds": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 300
                }
              }
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 256,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
      }
    }
  },
  "teams.config": {
    "kind": "object",
    "properties": {
      "authority": {
        "kind": "object",
        "properties": {
          "listenerId": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "pathPrefix": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 160
          },
          "requiredScopes": {
            "kind": "array",
            "items": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 128
            },
            "minimumItems": 0,
            "maximumItems": 32,
            "unique": true
          },
          "maxRoutes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxConcurrentRequests": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1024
          },
          "maxRequestBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "maxResponseBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16777216
          },
          "requestTimeoutMs": {
            "kind": "integer",
            "minimum": 100,
            "maximum": 4500
          }
        }
      },
      "projection": {
        "kind": "object",
        "properties": {
          "maxRelativePathBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxQueryParameters": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 256
          },
          "maxQueryBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 16384
          },
          "maxHeaderCount": {
            "kind": "integer",
            "minimum": 2,
            "maximum": 32
          },
          "maxHeaderBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8192
          },
          "maxHeaderValueBytes": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 2048
          }
        }
      },
      "store": {
        "kind": "object",
        "properties": {
          "path": {
            "kind": "string",
            "format": "absolute-path",
            "minimumLength": 1,
            "maximumLength": 4096
          },
          "maxDeliveries": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 1000000
          },
          "retentionHours": {
            "kind": "integer",
            "minimum": 1,
            "maximum": 8760
          }
        }
      },
      "profiles": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "tenantId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160
            },
            "workflowEndpoint": {
              "kind": "string",
              "format": "uri",
              "minimumLength": 1,
              "maximumLength": 2048,
              "schemes": [
                "http",
                "https"
              ],
              "requireHost": true,
              "allowFragment": false
            },
            "microsoftTenantId": {
              "kind": "string",
              "minimumLength": 36,
              "maximumLength": 64,
              "pattern": "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[1-5][0-9A-Fa-f]{3}-[89abAB][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}"
            },
            "teamId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160
            },
            "channels": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 160
              },
              "minimumItems": 1,
              "maximumItems": 256,
              "unique": true
            },
            "credentialBindingId": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "credentialReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "signingSecretReference": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 256
            },
            "webhookRoute": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 160,
              "pattern": "/[A-Za-z0-9._~/-]{1,159}"
            },
            "limits": {
              "kind": "object",
              "properties": {
                "requestTimeoutMs": {
                  "kind": "integer",
                  "minimum": 100,
                  "maximum": 30000
                },
                "maxRequestBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxResponseBytes": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 1048576
                },
                "maxTextChars": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 28000
                },
                "maxConcurrency": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 64
                },
                "maxPerSecond": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 50
                },
                "ackTimeoutMs": {
                  "kind": "integer",
                  "minimum": 100,
                  "maximum": 4500
                },
                "signatureMaxAgeSeconds": {
                  "kind": "integer",
                  "minimum": 1,
                  "maximum": 300
                }
              }
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 256,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"
      }
    }
  },
  "core.human-task:interactionDocument": {
    "kind": "object",
    "properties": {
      "schemaVersion": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 9007199254740991
      },
      "capabilityTtlSeconds": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 9007199254740991
      },
      "maxCompletionBytes": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 9007199254740991
      },
      "capabilitySecretBase64": {
        "kind": "object",
        "properties": {
          "$secret": {
            "kind": "string",
            "minimumLength": 1,
            "maximumLength": 4096
          }
        }
      },
      "profiles": {
        "kind": "array",
        "items": {
          "kind": "object",
          "properties": {
            "id": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 4096
            },
            "version": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 9007199254740991
            },
            "kind": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 4096
            },
            "launchUri": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 4096
            },
            "origin": {
              "kind": "string",
              "format": "uri",
              "minimumLength": 1,
              "maximumLength": 2048,
              "schemes": [
                "http",
                "https"
              ],
              "requireHost": true,
              "allowFragment": false
            },
            "completionSecretBase64": {
              "kind": "object",
              "properties": {
                "$secret": {
                  "kind": "string",
                  "minimumLength": 1,
                  "maximumLength": 4096
                }
              }
            }
          }
        },
        "minimumItems": 0,
        "maximumItems": 1024,
        "unique": true
      }
    }
  },
  "core.publication-policies:policyDocument": {
    "kind": "object",
    "properties": {
      "schemaVersion": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 9007199254740991
      },
      "policies": {
        "kind": "array",
        "items": {
          "kind": "object",
          "properties": {
            "id": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 4096
            },
            "version": {
              "kind": "string",
              "minimumLength": 1,
              "maximumLength": 4096
            },
            "maxCandidateBytes": {
              "kind": "integer",
              "minimum": 1,
              "maximum": 9007199254740991
            },
            "rules": {
              "kind": "array",
              "items": {
                "kind": "union",
                "choices": [
                  {
                    "kind": "object",
                    "properties": {
                      "type": {
                        "kind": "string",
                        "minimumLength": 1,
                        "maximumLength": 4096
                      },
                      "id": {
                        "kind": "string",
                        "minimumLength": 1,
                        "maximumLength": 4096
                      },
                      "allowedTypes": {
                        "kind": "array",
                        "items": {
                          "kind": "string",
                          "minimumLength": 1,
                          "maximumLength": 4096
                        },
                        "minimumItems": 0,
                        "maximumItems": 1024,
                        "unique": true
                      },
                      "allowedAddresses": {
                        "kind": "array",
                        "items": {
                          "kind": "string",
                          "minimumLength": 1,
                          "maximumLength": 4096
                        },
                        "minimumItems": 0,
                        "maximumItems": 1024,
                        "unique": true
                      }
                    }
                  },
                  {
                    "kind": "object",
                    "properties": {
                      "type": {
                        "kind": "string",
                        "minimumLength": 1,
                        "maximumLength": 4096
                      },
                      "id": {
                        "kind": "string",
                        "minimumLength": 1,
                        "maximumLength": 4096
                      },
                      "allowedSourceTypes": {
                        "kind": "array",
                        "items": {
                          "kind": "string",
                          "minimumLength": 1,
                          "maximumLength": 4096
                        },
                        "minimumItems": 0,
                        "maximumItems": 1024,
                        "unique": true
                      }
                    }
                  }
                ]
              },
              "minimumItems": 0,
              "maximumItems": 1024,
              "unique": true
            }
          }
        },
        "minimumItems": 0,
        "maximumItems": 1024,
        "unique": true
      }
    }
  },
  "core.runner:runnerDocument": {
    "kind": "object",
    "properties": {
      "protocolVersion": {
        "kind": "integer",
        "minimum": 1,
        "maximum": 9007199254740991
      },
      "runnerIssuer": {
        "kind": "string",
        "minimumLength": 1,
        "maximumLength": 4096
      },
      "artifactDirectory": {
        "kind": "string",
        "format": "absolute-path",
        "minimumLength": 1,
        "maximumLength": 4096
      },
      "tenants": {
        "kind": "map",
        "values": {
          "kind": "object",
          "properties": {
            "policy": {
              "kind": "object",
              "properties": {
                "capabilities": {
                  "kind": "array",
                  "items": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 4096
                  },
                  "minimumItems": 0,
                  "maximumItems": 1024,
                  "unique": true
                },
                "tools": {
                  "kind": "array",
                  "items": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 4096
                  },
                  "minimumItems": 0,
                  "maximumItems": 1024,
                  "unique": true
                },
                "network": {
                  "kind": "array",
                  "items": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 4096
                  },
                  "minimumItems": 0,
                  "maximumItems": 1024,
                  "unique": true
                },
                "secrets": {
                  "kind": "array",
                  "items": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 4096
                  },
                  "minimumItems": 0,
                  "maximumItems": 1024,
                  "unique": true
                },
                "mounts": {
                  "kind": "array",
                  "items": {
                    "kind": "string",
                    "minimumLength": 1,
                    "maximumLength": 4096
                  },
                  "minimumItems": 0,
                  "maximumItems": 1024,
                  "unique": true
                },
                "limits": {
                  "kind": "object",
                  "properties": {
                    "wallTime": {
                      "kind": "string",
                      "minimumLength": 1,
                      "maximumLength": 4096
                    },
                    "memoryBytes": {
                      "kind": "integer",
                      "minimum": 1,
                      "maximum": 9007199254740991
                    },
                    "processes": {
                      "kind": "integer",
                      "minimum": 1,
                      "maximum": 9007199254740991
                    },
                    "workspaceBytes": {
                      "kind": "integer",
                      "minimum": 1,
                      "maximum": 9007199254740991
                    },
                    "artifactBytes": {
                      "kind": "integer",
                      "minimum": 1,
                      "maximum": 9007199254740991
                    },
                    "logBytes": {
                      "kind": "integer",
                      "minimum": 1,
                      "maximum": 9007199254740991
                    },
                    "payloadBytes": {
                      "kind": "integer",
                      "minimum": 1,
                      "maximum": 9007199254740991
                    }
                  }
                }
              }
            },
            "definitions": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 4096
              },
              "minimumItems": 0,
              "maximumItems": 1024,
              "unique": true
            },
            "runners": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 4096
              },
              "minimumItems": 0,
              "maximumItems": 1024,
              "unique": true
            },
            "workspaceProfiles": {
              "kind": "array",
              "items": {
                "kind": "string",
                "minimumLength": 1,
                "maximumLength": 4096
              },
              "minimumItems": 0,
              "maximumItems": 1024,
              "unique": true
            }
          }
        },
        "minimumEntries": 1,
        "maximumEntries": 256,
        "keyPattern": "[A-Za-z0-9][A-Za-z0-9._-]{0,159}"
      }
    }
  }
} as const satisfies Readonly<Record<string, ValueSchema>>;
