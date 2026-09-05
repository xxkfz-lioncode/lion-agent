import request from './request'

export function listMcpServers() {
  return request.get('/mcp-server')
}

export function createMcpServer(data) {
  return request.post('/mcp-server', data)
}

export function updateMcpServer(id, data) {
  return request.put(`/mcp-server/${id}`, data)
}

export function deleteMcpServer(id) {
  return request.delete(`/mcp-server/${id}`)
}

export function connectMcpServer(id) {
  return request.post(`/mcp-server/${id}/connect`)
}

export function disconnectMcpServer(id) {
  return request.post(`/mcp-server/${id}/disconnect`)
}

export function discoverMcpServerTools(id) {
  return request.post(`/mcp-server/${id}/tools/discover`)
}

export function listMcpServerTools(id) {
  return request.get(`/mcp-server/${id}/tools`)
}

export function testMcpTool(serverId, toolName, argsJson) {
  return request.post(`/mcp-server/${serverId}/tools/${toolName}/test`, { argsJson })
}

export function listLocalTools() {
  return request.get('/mcp-server/local-tools')
}
