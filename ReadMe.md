# Ready For Nature

Checks for upcoming inclement weather and alerts you if you have doors or windows open.

This SmartThings app is a fork of [Ready for Rain](https://github.com/imbrianj/ready_for_rain), with the following enhancements:

* An option to check the hourly forecast instead of the whole day forecast, which is useful during days with changeable weather.
* The ability to send TTS alerts to a configured media speaker (devices with capability.musicPlayer)
* An option to check the Air Quality Index via the U.S. EPA [AirNow API](https://docs.airnowapi.org/) in addition to (or instead of) the rain forecast.

## Setup

1. Install the app into the IDE via GitHub integration (if you have this configured), or the via the old fashioned way of pasting the code into the New SmartApp > From Code window.
2. If you plan to use the Air Quality check, you will need to:
    1. [Request a free AirNow API key](https://docs.airnowapi.org/account/request/)
    2. [Log in](https://docs.airnowapi.org/login) to the AirNow API website.
    3. Visit any of the Web Services pages (e.g. [Forecast by Zip Code](https://docs.airnowapi.org/forecastsbyzip/docs)) and look for **Your API Key:** in the top right of the page. Copy the key to the clipboard.
    4. Go to the SmartThings IDE > My SmartApps > Ready For Nature and click on **App Settings** in the top right.
	5. Click **Settings** and paste in your API key in the **Value** box next to **airNowKey**
3. Once installed into the IDE, add and configure for your hub via the SmartThings mobile app.

## Acknowledgements

* Special thanks to [imbrianj](https://github.com/imbrianj) for the original **Ready for Rain** SmartApp and to [motley74](https://github.com/motley74) for his contributions.
* App icons provided courtesy of [WebHostFace](https://www.webhostface.com/blog/material-design-icons/).