/* Android LD_PRELOAD shim for the embedded librespot child.
 *
 * Some consumer routers advertise IPv6 without a working upstream. In that
 * situation librespot's dealer websocket can
 * choose the unreachable AAAA address and the Connect device then disappears.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <netdb.h>
#include <sys/socket.h>

int getaddrinfo(const char *node, const char *service,
                const struct addrinfo *hints, struct addrinfo **result) {
    static int (*real_getaddrinfo)(const char *, const char *,
                                   const struct addrinfo *, struct addrinfo **);
    if (!real_getaddrinfo)
        real_getaddrinfo = dlsym(RTLD_NEXT, "getaddrinfo");
    if (!real_getaddrinfo) return EAI_FAIL;

    struct addrinfo ipv4_hints = {0};
    if (hints) ipv4_hints = *hints;
    if (ipv4_hints.ai_family == AF_UNSPEC) ipv4_hints.ai_family = AF_INET;
    return real_getaddrinfo(node, service, &ipv4_hints, result);
}
