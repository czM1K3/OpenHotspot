import pytest

from openhotspot.chunking import Reassembler, chunk_frame
from openhotspot.constants import Band, ErrorCode, HotspotState, MsgType, Security
from openhotspot.errors import ProtocolError
from openhotspot.framing import Frame
from openhotspot.messages import HotspotInfo, Status, pack_enable_body, unpack_enable_body


def code_of(exc_info) -> ErrorCode:
    return exc_info.value.code


# ---- frames ------------------------------------------------------------------------------

def test_frame_roundtrip():
    f = Frame(MsgType.ENABLE, 0xBEEF, b"abc")
    raw = f.encode()
    assert raw == bytes([1, 0x10, 0, 0xBE, 0xEF, 0, 3]) + b"abc"
    assert Frame.decode(raw) == f


@pytest.mark.parametrize("raw,code", [
    (b"\x01\x10\x00", ErrorCode.MALFORMED),                           # shorter than header
    (b"\x02\x10\x00\x00\x01\x00\x00", ErrorCode.UNSUPPORTED_VERSION),
    (b"\x01\x77\x00\x00\x01\x00\x00", ErrorCode.MALFORMED),           # unknown type
    (b"\x01\x10\x02\x00\x01\x00\x00", ErrorCode.MALFORMED),           # unknown flag bit
    (b"\x01\x10\x01\x00\x01\x00\x00", ErrorCode.MALFORMED),           # plaintext flag on a non-error
    (b"\x01\x10\x00\x00\x01\x00\x05ab", ErrorCode.MALFORMED),         # length mismatch
    (b"\x01\x10\x00\x00\x01\x00\x00x", ErrorCode.MALFORMED),          # trailing byte
])
def test_frame_decode_rejects(raw, code):
    with pytest.raises(ProtocolError) as e:
        Frame.decode(raw)
    assert code_of(e) == code


def test_frame_size_limit():
    Frame(MsgType.ENABLE, 1, bytes(505)).encode()  # exactly 512 bytes
    with pytest.raises(ProtocolError):
        Frame(MsgType.ENABLE, 1, bytes(506)).encode()
    with pytest.raises(ProtocolError):
        Frame.decode(b"\x01\x10\x00\x00\x01\x01\xfa" + bytes(506))


def test_plaintext_error_frame_flag():
    f = Frame(MsgType.ERROR, 1, b"\x03\x00\x1e", flags=1)
    assert Frame.decode(f.encode()).plaintext


# ---- messages ----------------------------------------------------------------------------

def test_hotspot_info_roundtrip_and_off_state():
    info = HotspotInfo(HotspotState.ON, Security.WPA3_SAE, Band.GHZ_6, "ünï".encode(), "p" * 63)
    assert HotspotInfo.decode(info.encode()) == info
    off = HotspotInfo(HotspotState.OFF)
    assert off.encode() == bytes([0, 1, 0, 0, 0]) and HotspotInfo.decode(off.encode()) == off


@pytest.mark.parametrize("body", [
    b"", bytes([2, 1, 1, 33]) + bytes(40),                      # SSID too long
    bytes([2, 1, 1, 1]) + b"a" + bytes([7]) + b"x" * 7,         # passphrase 7 bytes
    bytes([2, 1, 1, 1]) + b"a" + bytes([8]) + b"x" * 8 + b"!",  # trailing byte
    bytes([9, 1, 1, 0, 0]),                                      # unknown state
])
def test_hotspot_info_rejects(body):
    with pytest.raises(ProtocolError):
        HotspotInfo.decode(body)


def test_hotspot_info_encode_validates():
    with pytest.raises(ProtocolError):
        HotspotInfo(HotspotState.ON, ssid=bytes(33)).encode()
    with pytest.raises(ProtocolError):
        HotspotInfo(HotspotState.ON, passphrase="short").encode()


def test_enable_body_and_status():
    assert unpack_enable_body(pack_enable_body(0, 600)) == (0, 600)
    with pytest.raises(ProtocolError):
        unpack_enable_body(b"\x00")
    s = Status(1, True, False, True)
    assert s.encode() == bytes([1, 0b101]) and Status.decode(s.encode()) == s
    with pytest.raises(ProtocolError):
        Status.decode(bytes([1, 0x08]))


# ---- chunking ----------------------------------------------------------------------------

def test_single_chunk_header_is_0xc0():
    assert chunk_frame(b"\x01" * 10, 23) == [b"\xc0" + b"\x01" * 10]


def test_chunk_sizes_and_flags():
    chunks = chunk_frame(bytes(range(50)), 23)  # 19 bytes of payload per chunk
    assert [len(c) for c in chunks] == [20, 20, 13]
    assert [c[0] for c in chunks] == [0x80, 0x01, 0x42]


@pytest.mark.parametrize("mtu", [23, 24, 100, 185, 247, 512])
@pytest.mark.parametrize("size", [7, 19, 20, 38, 151, 512])
def test_roundtrip_all_sizes(mtu, size):
    frame = Frame(MsgType.ENABLE, 7, bytes(size - 7)).encode()
    reasm, out = Reassembler(), None
    for i, c in enumerate(chunk_frame(frame, mtu)):
        assert len(c) <= mtu - 3
        out = reasm.feed(c)
    assert out == frame and not reasm.in_progress


def _frame(n=40):
    return Frame(MsgType.ENABLE, 1, bytes(n)).encode()


def test_mtu_below_minimum():
    with pytest.raises(ValueError):
        chunk_frame(_frame(), 22)


def test_gap_in_sequence_is_malformed_and_discards():
    chunks = chunk_frame(_frame(60), 23)
    r = Reassembler()
    r.feed(chunks[0])
    with pytest.raises(ProtocolError):
        r.feed(chunks[2])
    assert not r.in_progress
    with pytest.raises(ProtocolError):  # nothing in progress any more
        r.feed(chunks[1])


def test_continuation_without_first():
    with pytest.raises(ProtocolError):
        Reassembler().feed(b"\x41abc")


def test_first_restarts_a_partial_frame():
    a, b = chunk_frame(_frame(60), 23), chunk_frame(_frame(10), 23)
    r = Reassembler()
    r.feed(a[0])
    assert r.feed(b[0]) == _frame(10)


def test_first_with_nonzero_sequence():
    with pytest.raises(ProtocolError):
        Reassembler().feed(bytes([0x85]) + _frame())


def test_last_flag_must_agree_with_declared_length():
    chunks = chunk_frame(_frame(60), 23)
    early_last = bytes([chunks[0][0] | 0x40]) + chunks[0][1:]
    with pytest.raises(ProtocolError):
        Reassembler().feed(early_last)
    r = Reassembler()
    for c in chunks[:-1]:
        r.feed(c)
    with pytest.raises(ProtocolError):  # complete length but LAST missing
        r.feed(bytes([chunks[-1][0] & ~0x40]) + chunks[-1][1:])


def test_extra_data_beyond_declared_length():
    with pytest.raises(ProtocolError):
        Reassembler().feed(b"\xc0" + _frame() + b"x")


def test_declared_length_over_512():
    with pytest.raises(ProtocolError):
        Reassembler().feed(b"\x80" + b"\x01\x10\x00\x00\x01\x02\x00" + b"x" * 5)


def test_first_chunk_shorter_than_header():
    with pytest.raises(ProtocolError):
        Reassembler().feed(b"\x80\x01\x10")


def test_reassembly_timeout_discards_partial_frame():
    t = [0.0]
    r = Reassembler(lambda: t[0])
    chunks = chunk_frame(_frame(60), 23)
    r.feed(chunks[0])
    t[0] = 5.1
    with pytest.raises(ProtocolError):  # stale partial dropped, so the continuation is orphaned
        r.feed(chunks[1])
    t[0] = 0.0
    r2 = Reassembler(lambda: t[0])
    r2.feed(chunks[0]); t[0] = 4.9
    r2.feed(chunks[1])  # still inside the 5 s window
