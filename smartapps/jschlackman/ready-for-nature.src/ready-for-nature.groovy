/**
 *	Ready for Nature
 *
 *	Author: brian@bevey.org, james@schlackman.org, motley74@gmail.com
 *	Date: 9/9/17
 *
 *	Warn if doors or windows are open when inclement weather is approaching.
 *
 *	Changelog:
 *
 *	4/15/2016 by motley74 (motley74@gmail.com)
 *	Added ability to set delay before attempting to send message,
 *	will cancel alert if contacts closed within delay.
 *
 *	6/23/2017 by jschlackman (james@schlackman.org)
 *	Added option to use hourly forecast instead of daily.
 *	Added option for TTS notifications on a connected media player.
 *
 *	7/11/2017 by jschlackman (james@schlackman.org)
 *	Added option to include the chain of rain in the alert message.
 *
 *	9/9/2017 by jschlackman (james@schlackman.org)
 *	Added option to check air quality as well as (or instead of) rain.
 *
 */

definition(
	name: "Ready For Nature",
	namespace: "jschlackman",
	author: "brian@bevey.org, james@schlackman.org, motley74@gmail.com",
	description: "Warn if doors or windows are open when inclement weather is approaching.",
	category: "Convenience",
	iconUrl: "https://raw.githubusercontent.com/jschlackman/ReadyForNature/master/smartapp-icons/ready-for-nature.png",
	iconX2Url: "https://raw.githubusercontent.com/jschlackman/ReadyForNature/master/smartapp-icons/ready-for-nature-x2.png",
	iconX3Url: "https://raw.githubusercontent.com/jschlackman/ReadyForNature/master/smartapp-icons/ready-for-nature-x3.png"
) {
	appSetting "airNowKey"
}

preferences {
	section("Zip Code") {
		input "checkZip", "text", title: "Enter zip code to check, or leave blank to use hub location.", required: false
	}

	section("Forecast Options") {
		input "forecastType", "enum", title: "Forecast range", options: ["Today", "Next Hour"], defaultValue: "Today", required: true
		input "checkRain", "enum", title: "Check for rain?", options: ["Yes", "No"], defaultValue: "Yes", required: true
		input "checkAir", "enum", title: "Check air quality? (Requires API key to be set in IDE)", options: ["Yes", "No"], defaultValue: "No", required: true
		input "airNowCat", "enum", title: "Alert on this air quality or worse", required: true, defaultValue: 2, options: [
			1:"Good",
			2:"Moderate",
			3:"Unhealthy for Sensitive Groups",
			4:"Unhealthy",
			5:"Very Unhealthy",
			6:"Hazardous"
		]
	}

	section("Things to check") {
		input "sensors", "capability.contactSensor", title: "Check if these contacts are open" , multiple: true
	}

	section("Notifications") {
		input "sendPushMessage", "enum", title: "Send a push notification", options: ["Yes", "No"], defaultValue: "No", required: true
		input "phone", "phone", title: "Send a Text Message to number", required: false
	}

	section("Audio alerts", hideWhenEmpty: true) {
		input "sonos", "capability.musicPlayer", title: "Play on this Music Player", required: false, multiple: true, submitOnChange: true
		input "sonosVolume", "number", title: "Temporarily change volume", description: "0-100%", required: false, hideWhenEmpty: "sonos"
		input "resumePlaying", "bool", title: "Resume currently playing music after notification", required: false, defaultValue: false, hideWhenEmpty: "sonos"
	}

	section("Message options") {
		input "messageDelay", "number", title: "Delay before sending initial message? Minutes (default to no delay)", required: false
		input "messageReset", "number", title: "Delay before sending secondary messages? Minutes (default to every message)", required: false
		input "messageRainChance", "enum", title: "Include chance of rain in message?", options: ["Yes", "No"], defaultValue: "No", required: true
	}
}

def installed() {
	log.debug("${app.label} installed with settings: ${settings}")
	init()
}

def updated() {
	log.debug("${app.label} updated with settings: ${settings}")
	unsubscribe()
	unschedule()
	init()
}

def init() {
	state.lastMessage = 0
	state.lastCheck = ["time": 0, "result": false]
	schedule("0 0,30 * * * ?", scheduleCheck) // Check at top and half-past of every hour
	subscribe(sensors, "contact.open", scheduleCheck)
}

def scheduleCheck(evt) {
	def open = sensors.findAll { it?.latestValue("contact") == "open" }
	def waitTime = messageDelay ? messageDelay * 60 : 0
	def weatherFeature = null
	def sendAlert = false

	def expireWeather = (now() - (30 * 60 * 1000))
	// Only need to poll if we haven't checked since defined expiry time - and if something is left open.
	if(!open) {
		log.info("Everything looks closed, no reason to check weather.")
	} else if(expireWeather > state.lastCheck["time"]) {
		log.info("Something's open, let's check the weather.")

	// If configured to check for rain, get the forecast.
	if(checkRain == "Yes") {
	
		// Get the forecast type specified in the options.
		if(forecastType == "Today") {
			weatherFeature = getWeatherFeature("forecast", checkZip)
			state.weatherForecast = weatherFeature?.forecast?.txt_forecast?.forecastday?.first()
		} else {
			weatherFeature = getWeatherFeature("hourly", checkZip)
			state.weatherForecast = weatherFeature?.hourly_forecast?.first()
		}
		def weather = isStormy(state.weatherForecast)

		if(weather) {
			sendAlert = true
		}
	}

	// If configured to check air quality, get the AQI from AirNow.
	if(checkAir) {
		state.airCategory = airNowCategory()
		if(state.airCategory.number >= airNowCat.toInteger()) {
			sendAlert = true
		}

	}

	// Send alert if either rain or AQI check requires it.
	if(sendAlert) {
		runIn(waitTime, "send", [overwrite: false])
	}

	
	} else if(state.lastCheck["result"]) {
		log.info("We have fresh weather data, inclement weather is expected.")
		runIn(waitTime, "send", [overwrite: false])
	} else {
		log.info("We have fresh weather data, weather looks fine.")
	}
}

def send() {
	def delay = (messageReset != null && messageReset != "") ? messageReset * 60 * 1000 : 0
	def open = sensors.findAll { it?.latestValue("contact") == "open" }
	def plural = open.size() > 1 ? "are" : "is"
	def weather = null
	def msg = "${open.join(', ')} ${plural} open and"

	// Check the rain forecast if requested by user
	if(checkRain == "Yes") {
		weather = isStormy(state.weatherForecast)
	}

	// Send message about rain if it is expected.
	if(weather) {
		msg = msg + " ${weather} coming. "

		// Report chance of rain if requested by user.
		if (messageRainChance == "Yes") {
			def rain = rainChance(state.weatherForecast)
			msg = msg + "Chance of rain ${rain}. "
		}
	}

	// Send message about air quality if it meets or exceeds the requested alert category
	if(state.airCategory.number >= airNowCat.toInteger()) {
		
		msg = msg + " Air Quality "
		if(forecastType == "Today") {
			msg = msg + "forecast " 
		}
		msg = msg + "is ${state.airCategory.name}."
	}

	if(open) {
		if(now() - delay > state.lastMessage) {
			state.lastMessage = now()
			if(sendPushMessage == "Yes") {
				log.debug("Sending push message.")
				sendPush(msg)
			}

			if(phone) {
				log.debug("Sending text message.")
				sendSms(phone, msg)
			}

			// Send a TTS message if configured
			if(sonos) {
				def sonosCommand = resumePlaying == true ? "playTrackAndResume" : "playTrackAndRestore"
				def ttsMsg = textToSpeech(msg)

				// Send message with a custom volume level if requested
				if(sonosVolume) {
					sonos."${sonosCommand}"(ttsMsg.uri, ttsMsg.duration, sonosVolume)
				} else {
					sonos."${sonosCommand}"(ttsMsg.uri, ttsMsg.duration)
				}
			}

			log.debug(msg)
		} else {
			log.info("Have a message to send, but user requested to not get it.")
		}
	} else {
		log.info("Everything closed before timeout.")
	}
}

private isStormy(forecast) {
	
	// List of WU phrases indicating precipitation
	// https://www.wunderground.com/weather/api/d/docs?d=resources/phrase-glossary#forecast_description_phrases
	def types = ["rain", "snow", "showers", "sprinkles", "precipitation", "thunderstorm", "sleet", "flurries"]
	def result = false

	if(forecast) {
		def text = null
		
		// Parse the JSON according to the type of forecast (daily or hourly)
		if(forecastType == "Today") {
			text = forecast?.fcttext?.toLowerCase()
		} else {
			text = forecast?.condition?.toLowerCase()
		}

		log.debug("Forecast conditions: ${text}")

		// Check the forecast text for each of the precipitation types until we find one or exhaust the list
		if(text) {
			for (int i = 0; i < types.size() && !result; i++) {
				if(text.contains(types[i])) {
					result = types[i]
				}
			}
		} else {
			log.warn("Got forecast, couldn't parse.")
		}
	} else {
		log.warn("Did not get a forecast: ${json}")
	}

	state.lastCheck = ["time": now(), "result": result]

	return result
}

// Pull the percentage change of rain from stored weather forecast data
private rainChance(forecast) {
	def result = false

	if(forecast) {
		result = forecast?.pop + "%"
	}	

	return result
}

// Get air quality category data from the AirNow API
private airNowCategory() {
	def result = null
	def airZip = null

	// Use hub zipcode if user has not defined their own
	if(checkZip) {
		airZip = checkZip
	} else {
		airZip = location.zipCode
	}

	def requestPath = ''

	// Select whether to query the forecast for today or current conditions
	if(forecastType == "Today") {
		requestPath = 'forecast/zipCode/'
	} else {
		requestPath = 'observation/zipCode/current/'
	}
    
    log.debug("Getting AirNow data: ${requestPath}")
    
	// Set up the AirNow API query
	def params = [
		uri: 'http://www.airnowapi.org/aq/',
		path: requestPath,
		contentType: 'application/json',
		query: [format:'application/json', zipCode: airZip, distance: 25, API_KEY: appSettings.airNowKey]
	]

	try {
	// Send query to the AirNow API
		httpGet(params) {resp ->
            state.aqi = resp.data
			// Print the AQI numbers and categories for both PM2.5 and O3 to the debug log.
			log.debug("${resp.data[0].ParameterName}: ${resp.data[0].AQI}, ${resp.data[0].Category.Name} (${resp.data[0].Category.Number})")
			log.debug("${resp.data[1].ParameterName}: ${resp.data[1].AQI}, ${resp.data[1].Category.Name} (${resp.data[1].Category.Number})")

			// We're only interested in whichever is the worst of the 2 categories, so figure out which one has the higher number and store it
			if(resp.data[0].Category.Number.toInteger() > resp.data[1].Category.Number.toInteger()) {
				result = ["name": resp.data[0].Category.Name, "number": resp.data[0].Category.Number.toInteger()]
			} else {
				result = ["name": resp.data[1].Category.Name, "number": resp.data[1].Category.Number.toInteger()]
			}						
		}

		// Ignore AQI result if it is less than the configured alert category
		if(result.number >= airNowCat.toInteger()) {
			state.lastCheck = ["time": now(), "result": result]
		} else {
			state.lastCheck = ["time": now(), "result": false]
		}
				
	}
	catch (e) {
		log.error("Could not retrieve AQI: $e")
        // AQI information could not be retrieved
        result = ["name": "Unavailable", "number": 0]
	}

	return result
}