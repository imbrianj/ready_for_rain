/**
 *  Ready for Rain
 *
 *  Author: brian@bevey.org
 *  Date: 9/10/13
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
 *  Added option to use hourly forecast instead of daily
 *  Added option for TTS notifications on a connected media player
 *
 */

definition(
  name: "Ready For Rain",
  namespace: "imbrianj",
  author: "brian@bevey.org",
  description: "Warn if doors or windows are open when inclement weather is approaching.",
  category: "Convenience",
  iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
  iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience%402x.png"
)

preferences {
  section("Zip code?") {
    input "zipcode", "text", title: "Zipcode?", required: false
  }

  section("Forecast to check?") {
	input "forecastType", "enum", title: "Rain expected when?", metadata: [values: ["Today", "Next Hour"]]
  }
  
  section("Things to check?") {
    input "sensors", "capability.contactSensor", multiple: true
  }

  section("Notifications?") {
    input "sendPushMessage", "enum", title: "Send a push notification?", metadata: [values: ["Yes", "No"]], required: false
    input "phone", "phone", title: "Send a Text Message?", required: false
  }

  section("Audio alerts?") {
	input "sonos", "capability.musicPlayer", title: "Play on this Music Player", required: false, multiple: true, submitOnChange: true
    input "sonosVolume", "number", title: "Temporarily change volume", description: "0-100%", required: false
	input "resumePlaying", "bool", title: "Resume currently playing music after notification", required: false, defaultValue: false
  }  

  section("Message options?") {
    input name: "messageDelay", type: "number", title: "Delay before sending initial message? Minutes (default to no delay)", required: false
    input name: "messageReset", type: "number", title: "Delay before sending secondary messages? Minutes (default to every message)", required: false
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
  
  def expireWeather = (now() - (30 * 60 * 1000))
  // Only need to poll if we haven't checked since defined expiry time - and if something is left open.
  if(!open) {
    log.info("Everything looks closed, no reason to check weather.")
  } else if(expireWeather > state.lastCheck["time"]) {
    log.info("Something's open, let's check the weather.")
	
	// Get the forecast type specified in the options
	if(forecastType == "Today") {
	  weatherFeature = getWeatherFeature("forecast", zipcode)
      state.weatherForecast = weatherFeature?.forecast?.txt_forecast?.forecastday?.first()
	} else {
      weatherFeature = getWeatherFeature("hourly", zipcode)
	  state.weatherForecast = weatherFeature?.hourly_forecast?.first()
	}
    def weather = isStormy(state.weatherForecast)

    if(weather) {
      runIn(waitTime, "send", [overwrite: false])
    }
  } else if(state.lastCheck["result"]) {
    log.info("We have fresh weather data, rain is expected.")
    runIn(waitTime, "send", [overwrite: false])
  } else {
    log.info("We have fresh weather data, rain is not expected.")
  }
}

def send() {
  def delay = (messageReset != null && messageReset != "") ? messageReset * 60 * 1000 : 0
  def open = sensors.findAll { it?.latestValue("contact") == "open" }
  def plural = open.size() > 1 ? "are" : "is"
  def weather = isStormy(state.weatherForecast)
  def rain = rainChance(state.weatherForecast)
  def msg = "${open.join(', ')} ${plural} open and ${weather} coming. Chance of rain ${rain}."

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

    log.debug("Forecast conditions: ${text}.")

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