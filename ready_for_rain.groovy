/**
 *  Ready for Nature
 *
 *  Author: brian@bevey.org, james@schlackman.org
 *  Date: 9/9/17
 *
 *  Warn if doors or windows are open when inclement weather is approaching.
 *
 *  Changelog:
 *
 *  4/15/2016 by motley74 (motley74@gmail.com)
 *  Added ability to set delay before attempting to send message,
 *  will cancel alert if contacts closed within delay.
 *
 *  6/23/2017 by jschlackman (jay@schlackman.org)
 *  Added option to use hourly forecast instead of daily.
 *  Added option for TTS notifications on a connected media player.
 *
 *  7/11/2017 by jschlackman (jay@schlackman.org)
 *  Added option to include the chain of rain in the alert message.
 *
 *  9/9/2017 by jschlackman (jay@schlackman.org)
 *  Added option to check air quality as well as (or instead of) rain.
 *
 */

definition(
  name: "Ready For Nature",
  namespace: "jschlackman",
  author: "brian@bevey.org, james@schlackman.org",
  description: "Warn if doors or windows are open when inclement weather is approaching.",
  category: "Convenience",
  iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
  iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience%402x.png"
)

preferences {
  section("Zip Code") {
    input "checkZip", "text", title: "Enter zip code to check, or leave blank to use hub location.", required: false
  }

  section("Forecast Options") {
	input "forecastType", "enum", title: "Forecast range", options: ["Today", "Next Hour"], defaultValue: "Today", required: true
	input "checkRain", "enum", title: "Check for rain?", options: ["Yes", "No"], defaultValue: "Yes", required: true
    input "airNowKey", "text", title: "Check air quality? (Paste your AirNow API key here to use this feature)", required: false
    input "airNowCat", "enum", title: "Alert on this air quality or worse", hideWhenEmpty: "airNowKey", required: true, defaultValue: 2, options: [
      1:"Good",
	  2:"Moderate",
      3:"Unhealthy for Sensitive Groups",
      4:"Unhealthy",
      5:"Very Unhealthy",
      6:"Hazardous"]
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
  init()
}

def updated() {
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
	if(airNowKey) {
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
      
      if(sonos) {
        def sonosCommand = resumePlaying == true ? "playTrackAndResume" : "playTrackAndRestore"
        def ttsMsg = textToSpeech(msg)
        
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
  def types    = ["rain", "snow", "showers", "sprinkles", "precipitation", "thunderstorm", "sleet", "flurries"]
  def result   = false

  if(forecast) {
    def text = null
    if(forecastType == "Today") {
      text = forecast?.fcttext?.toLowerCase()
    } else {
      text = forecast?.condition?.toLowerCase()
    }

    log.debug("Forecast conditions: ${text}")

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

private rainChance(forecast) {
  def result = false
  
  if(forecast) {
    def text = null
    if(forecastType == "Today") {
      result = forecast?.pop + "%"
    } else {
      result = forecast?.pop + "%"
    }
  }  
    
  return result
}


private airNowCategory() {
	def result = null
	def airZip = null

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
    
	def params = [
        uri:  'http://www.airnowapi.org/aq/',
        path: requestPath,
        contentType: 'application/json',
        query: [format:'application/json', zipCode: airZip, distance: 25, API_KEY: airNowKey]
    ]
    try {
        httpGet(params) {resp ->
            state.aqi = resp.data
            log.debug("${resp.data[0].ParameterName}: ${resp.data[0].AQI}, ${resp.data[0].Category.Name} (${resp.data[0].Category.Number})")
            log.debug("${resp.data[1].ParameterName}: ${resp.data[1].AQI}, ${resp.data[1].Category.Name} (${resp.data[1].Category.Number})")
			
            if(resp.data[0].Category.Number.toInteger() > resp.data[1].Category.Number.toInteger()) {
            	result = ["name": resp.data[0].Category.Name, "number": resp.data[0].Category.Number.toInteger()]
            } else {
            	result = ["name": resp.data[1].Category.Name, "number": resp.data[1].Category.Number.toInteger()]
			}            
        }

		state.lastCheck = ["time": now(), "result": result]

	} catch (e) {
        log.error("error: $e")
    }

    return result
}