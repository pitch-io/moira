module.exports = function (config) {
  config.set({
    browsers: ['ChromeHeadless'],
    basePath: 'target/test',
    client: {
      args: ['shadow.test.karma.init'],
      singleRun: true,
    },
    colors: true,
    files: ['all.js'],
    frameworks: ['cljs-test'],
    logLevel: config.LOG_INFO,
    plugins: ['karma-cljs-test', 'karma-chrome-launcher'],
  })
}
