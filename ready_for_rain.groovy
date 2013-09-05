/**
 *  Ready for Rain
 *
 *  Author: brian@bevey.org
 *  Date: 9/5/13
 *  Warn if doors or windows are open when inclement weather is approaching.
 *
 *  Largely built from When It's Going To Rain and Severe Weather Alert.
 */
preferences {
  section("Zip code?"){
    input "zipcode", "text", title: "Zipcode?"
  }

  section("Things to check?"){
    input "sensors", "capability.contactSensor", multiple: true
  }

  section( "Notifications" ) {
    input "sendPushMessage", "enum", title: "Send a push notification?", metadata:[values:["Yes","No"]], required:false
    input "phone", "phone", title: "Send a Text Message?", required: false
  }
}

def installed() {
  log.debug("Installed with settings: ${settings}")
  schedule("0 0,30 * * * ?", scheduleCheck) // Check at top and half-past of every hour
}

def updated() {
  unsubscribe()
  log.debug("Updated with settings: ${settings}")
  schedule("0 0,30 * * * ?", scheduleCheck) // Check at top and half-past of every hour
}

def scheduleCheck() {
  def open = sensors.findAll { it?.latestValue("contact") == 'open' }

  // Only need to poll if something is left open.
  if (open) {
    log.info("Something's open - let's check the weather.")
    def response = getWeatherFeature("forecast", zipcode)
    def weather  = isStormy(response)

    if (weather) {
      send("Reported ${weather} coming and the following things are open: ${open.join(', ')}")
    }
  }

  else {
  	log.info("Everything looks closed, no reason to check weather.")
  }
}

private send(msg) {
  if (sendPushMessage != "No") {
    log.debug("sending push message")
    sendPush(msg)
  }

  if (phone) {
    log.debug("sending text message")
    sendSms(phone, msg)
  }

  log.debug(msg)
}

private isStormy(json) {
  def types    = ['rain', 'snow', 'showers', 'sprinkles', 'precipitation']
  def forecast = json?.forecast?.txt_forecast?.forecastday?.first()
  def result   = false

  if (forecast) {
    def text = forecast?.fcttext?.toLowerCase()

    log.debug(text)

    if (text) {
      for (int i = 0; i < types.size() && !result; i++) {
        if(text.contains(types[i])) {
          result = types[i]
        }
      }
    }

    else {
      log.warn("Got forecast, couldn't parse")
    }
  }

  else {
    log.warn("Did not get a forecast: ${json}")
  }

  return result
}