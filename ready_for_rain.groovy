/**
 *  Ready for Rain
 *
 *  Author: brian@bevey.org
 *  Date: 4/15/16
 *
 *  Warn if doors or windows are open when inclement weather is approaching.
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
    input "zipcode", "text", title: "Zipcode?"
  }

  section("Things to check?") {
    input "sensors", "capability.contactSensor", multiple: true
  }

  section("Notifications?") {
    input "sendPushMessage", "enum", title: "Send a push notification?", metadata: [values: ["Yes", "No"]], required: false
    input "phone", "phone", title: "Send a Text Message?", required: false
  }

  section("Message interval?") {
    input name: "messageInterval", type: "number", title: "Minutes (default to every message)", required: false
  }

  section("Delay to wait before sending notification (defaults to 1 minute)") {
    input "messageDelay", "decimal", title: "Number of minutes", required: false
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
  def delay = (messaegDelay != null && messageDelay != "") ? messageDelay * 60 : 60

  // Only need to poll if we haven't checked in a while - and if something is left open.
  if((now() - (30 * 60 * 1000) > state.lastCheck["time"]) && open) {
    log.info("Something's open - let's check the weather.")
    def response = getWeatherFeature("forecast", zipcode)
    def weather  = isStormy(response)

    if(weather) {
      log.info("Looks like bad weather.  Let's trigger a message.")
      runIn(delay, "messageTrigger")
    }
  }

  else if(((now() - (30 * 60 * 1000) <= state.lastCheck["time"]) && state.lastCheck["result"]) && open) {
    log.info("We have fresh weather data, no need to poll.")
    runIn(delay, "messageTrigger")
  }

  else {
    log.info("Everything looks closed, no reason to check weather.")
  }
}

private messageTrigger() {
  def open = sensors.findAll { it?.latestValue("contact") == "open" }
  def plural = open.size() > 1 ? "are" : "is"
  def weather = ""

  // Let's check again since device states and weather may have changed since triggered.
  if((now() - (30 * 60 * 1000) > state.lastCheck["time"]) && open) {
    def response = getWeatherFeature("forecast", zipcode)
    weather = isStormy(response)
  }

  else if(((now() - (30 * 60 * 1000) <= state.lastCheck["time"]) && state.lastCheck["result"]) && open) {
    weather = state.lastCheck["result"]
  }

  if(weather) {
    send("${open.join(', ')} ${plural} open and ${weather} coming.")
  }

  else {
    log.info("No need to send message.")
  }
}

private send(msg) {
  def delay = (messageInterval != null && messageInterval != "") ? messageInterval * 60 * 1000 : 0

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

    log.debug(msg)
  }

  else {
    log.info("Have a message to send, but user requested to not get it.")
  }
}

private isStormy(json) {
  def types    = ["rain", "snow", "showers", "sprinkles", "precipitation"]
  def forecast = json?.forecast?.txt_forecast?.forecastday?.first()
  def result   = false

  if(forecast) {
    def text = forecast?.fcttext?.toLowerCase()

    log.debug(text)

    if(text) {
      for (int i = 0; i < types.size() && !result; i++) {
        if(text.contains(types[i])) {
          result = types[i]
        }
      }
    }

    else {
      log.warn("Got forecast, couldn't parse.")
    }
  }

  else {
    log.warn("Did not get a forecast: ${json}")
  }

  state.lastCheck = ["time": now(), "result": result]

  return result
}
