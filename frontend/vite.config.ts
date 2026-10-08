import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig(({mode})=>{
  const target=loadEnv(mode,'.','VITE_API_PROXY_TARGET').VITE_API_PROXY_TARGET || 'http://127.0.0.1:8080'
  const url=new URL(target)
  if(!['http:','https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash)
    throw new Error('VITE_API_PROXY_TARGET must be an HTTP(S) backend URL without credentials, query or fragment')
  return {plugins:[vue()],server:{port:5173,proxy:{
    '/api':{target,changeOrigin:true},'/mcp':{target,changeOrigin:true}
  }}}
})
