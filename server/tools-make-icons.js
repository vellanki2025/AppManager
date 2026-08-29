// Generates flat placeholder PNG icons with no image dependencies.
const fs = require('fs'), zlib = require('zlib'), path = require('path');

function crc32(buf) {
  let c, table = [];
  for (let n = 0; n < 256; n++) { c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; table[n] = c >>> 0; }
  let crc = 0xffffffff;
  for (const b of buf) crc = table[(crc ^ b) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function png(size, rgb, accent) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 2; // 8-bit RGB
  const raw = [];
  const c = size / 2, r = size / 3.2;
  for (let y = 0; y < size; y++) {
    raw.push(0);
    for (let x = 0; x < size; x++) {
      const inCircle = (x - c) ** 2 + (y - c) ** 2 < r * r;
      const p = inCircle ? accent : rgb;
      raw.push(p[0], p[1], p[2]);
    }
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(Buffer.from(raw))),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const out = path.join(__dirname, 'icons');
fs.mkdirSync(out, { recursive: true });
const icons = {
  'lucid-media.png': [[11, 61, 92], [53, 167, 194]],
  'lucid-radio.png': [[38, 50, 80], [235, 174, 52]],
  'spotify.png': [[25, 20, 20], [30, 215, 96]],
};
for (const [name, [bg, fg]] of Object.entries(icons)) {
  fs.writeFileSync(path.join(out, name), png(192, bg, fg));
  console.log('wrote icons/' + name);
}
