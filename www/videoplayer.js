var exec = require("cordova/exec");

module.exports = {
    play: function (path, drmLicensePath, spoofIpAddress, successCallback, errorCallback) {
        exec(successCallback, errorCallback, "VideoPlayer", "play", [path, drmLicensePath, spoofIpAddress]);
    },

    close: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, "VideoPlayer", "close", []);
    }

};
