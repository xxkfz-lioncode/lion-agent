<template>
  <div class="swagger-page">
    <div class="swagger-frame-wrap">
      <iframe
        :src="swaggerUrl"
        class="swagger-frame"
        title="接口文档"
        allow="fullscreen"
      ></iframe>
    </div>
  </div>
</template>

<script setup>
// 后端 Swagger 接口文档地址，可用环境变量 VITE_SWAGGER_URL 覆盖
const swaggerUrl =
  import.meta.env.VITE_SWAGGER_URL || 'http://localhost:8080/swagger-ui/index.html'
</script>

<style scoped>
.swagger-page {
  height: 100%;
  overflow: hidden;
  background: #f7f8fc;
  padding: 12px;
}

.swagger-frame-wrap {
  height: 100%;
  background: #fff;
  border: 1px solid var(--border, #e4e6eb);
  border-radius: 10px;
  overflow: hidden;
}

.swagger-frame {
  width: 100%;
  height: 100%;
  border: none;
  display: block;
}
</style>
