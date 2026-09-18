// 测试用的“只接受连接、永不回应”的 TCP 服务器。
// 用途：验证配置页“测试连接”的有限总时限——应用与它建立 TCP 连接后，
// 它会一直不返回任何字节，从而制造出最坏情况（客户端流式读取不设超时）。
// 只监听本机，不对外提供服务。
const net = require('net');

const PORT = Number(process.argv[2] || 9443);
let conns = 0;

const server = net.createServer((socket) => {
  conns += 1;
  const stamp = new Date().toISOString();
  console.log(`[${stamp}] accepted connection #${conns} from ${socket.remoteAddress}`);
  socket.on('data', () => { /* 故意什么都不回 */ });
  socket.on('error', () => {});
  // 不 write、不 end：让对端一直等
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`hanging server listening on 0.0.0.0:${PORT}`);
});
