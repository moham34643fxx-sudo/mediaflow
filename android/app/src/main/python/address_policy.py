"""Conservative public-unicast policy shared by semantics with Kotlin UrlPolicy."""
import ipaddress

_v4 = tuple(ipaddress.ip_network(n) for n in (
    '0.0.0.0/8', '10.0.0.0/8', '100.64.0.0/10', '127.0.0.0/8',
    '169.254.0.0/16', '172.16.0.0/12', '192.0.0.0/16', '192.168.0.0/16',
    '198.18.0.0/15', '198.51.100.0/24', '203.0.113.0/24', '224.0.0.0/3'))
_v6 = tuple(ipaddress.ip_network(n) for n in ('2001::/23', '2001:db8::/32', '2002::/16', '3fff::/20'))

def public_address(value):
    address = ipaddress.ip_address(value)
    if address.version == 4:
        return not any(address in net for net in _v4)
    if address.ipv4_mapped:
        return public_address(str(address.ipv4_mapped))
    if address in ipaddress.ip_network('64:ff9b::/96'):
        return public_address(str(ipaddress.IPv4Address(int(address) & 0xffffffff)))
    return address in ipaddress.ip_network('2000::/3') and not any(address in net for net in _v6)
