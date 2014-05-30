# clj-docker

A wrapper for the [docker API][docker-api].

This supports only the http endpoint, not the UNIX socket endpoint.

## Usage

There are several ways of using the api.

The simplest is to call the direct function wrappers.

```clj
(require '[com.palletops.docker :refer :all])
(def container-id "246aaf4361e770c7f76204cfc16e52f9d134d8cc9d5774b8fba65e0fc6fddd94")
(container-processes "http://your-docker-host:4243" {:id container-id})
```

For a more data based approach, you can use the `docker` multi-method.

```clj
(require '[com.palletops.docker :refer :all])
(def container-id "246aaf4361e770c7f76204cfc16e52f9d134d8cc9d5774b8fba65e0fc6fddd94")
(docker "http://your-docker-host:4243" {:command :container-processes :id container-id})
```

## License

Copyright © 2014 Hugo Duncan

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

[docker-api]: http://docs.docker.io/reference/api/docker_remote_api_v1.11/ "Docker API"
