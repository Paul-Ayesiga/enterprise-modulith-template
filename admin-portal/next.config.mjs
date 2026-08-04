/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Self-contained server bundle for the Docker image (deploy/helm) — node_modules stays out.
  output: 'standalone'
};

export default nextConfig;
