const fs = require('fs');

const p42 = fs.readFileSync('benchmark/phase4_2_suite.js', 'utf8');
const p43 = fs.readFileSync('benchmark/phase4_3_suite.js', 'utf8');

function extractFunc(src, name) {
  const idx = src.indexOf(`async function ${name}`);
  if (idx === -1) return '';
  const next = src.indexOf('async function', idx + 1);
  return next !== -1 ? src.substring(idx, next) : src.substring(idx);
}

console.log('=== seedUsers Comparison ===');
const p42Seed = extractFunc(p42, 'seedUsers');
const p43Seed = extractFunc(p43, 'seedUsers');
console.log('p42Seed === p43Seed:', p42Seed.trim() === p43Seed.trim());

// Let's check hotWallet handling in both
console.log('=== hotWallet handling ===');
function getHotWalletCode(src) {
  const lines = src.split('\n');
  return lines.filter(l => l.includes('hotWallet')).join('\n');
}
console.log('P42 hotWallet mentions:');
console.log(getHotWalletCode(p42));
console.log('---');
console.log('P43 hotWallet mentions:');
console.log(getHotWalletCode(p43));
