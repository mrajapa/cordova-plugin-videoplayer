var exec = require("cordova/exec");

module.exports = {
    play: function (path, drmLicensePath, successCallback, errorCallback) {
        exec(successCallback, errorCallback, "VideoPlayer", "play", [path, drmLicensePath]);
    },

    close: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, "VideoPlayer", "close", []);
    }

};
