import path from 'path';
import webpack from 'webpack';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);


var config = {
  mode: "development",
  externals: {
    'react': 'React',
    'react-dom': 'ReactDOM',
  },
  module: {
    rules: [
      {
        // docs: https://webpack.js.org/configuration/module/#resolvefullyspecified
        test: /\.m?js/,
        resolve: {
          fullySpecified: false,
        }
      }
    ]
  },
  plugins: [
    // fix "process is not defined" error:
    new webpack.ProvidePlugin({
      process: 'process/browser',
    }),
  ],
};

var AppConfig = Object.assign({}, config, {
  name: "app",
  entry: {
    "db-worker" : "./target/db-worker.js",
    "inference-worker" : "./target/inference-worker.js"
  },

  output: {
    path: path.resolve(__dirname, 'static/js'),
    filename: '[name]-bundle.js',
    clean: false,
    chunkLoading: false,
  },
});

var MobileConfig = Object.assign({}, config, {
  name: "mobile",
  entry: {
    "db-worker" : "./target/db-worker.js",
  },

  output: {
    path: path.resolve(__dirname, 'static/mobile/js'),
    filename: '[name]-bundle.js',
    clean: false,
    chunkLoading: false,
  },
});

export default [
  AppConfig, MobileConfig,
];
