/**
 *  Ready for Rain
 *
 *  Author: brian@bevey.org
 *  Date: 9/3/13
 *  Updated from When It's Going To Rain to send push notifications as well.
 *  Added cron-like polling from Severe Weather Alert.
 */
preferences {
  section("Zip code..."){
    input "zipcode", "text", title: "Zipcode?"
  }

  section("Things to check..."){
    input "sensors", "capability.contactSensor", multiple: true
  }

  section( "Notifications" ) {
    input "sendPushMessage", "enum", title: "Send a push notification?", metadata:[values:["Yes","No"]], required:false
    input "phone", "phone", title: "Send a Text Message?", required: false
  }
}

def installed() {
  log.debug("Installed with settings: ${settings}")
  schedule("0 */10 * * * ?", "scheduleCheck") //Check at top and half-past of every hour
}

def updated() {
  log.debug("Updated with settings: ${settings}")
  unsubscribe()
  schedule("0 */10 * * * ?", "scheduleCheck") //Check at top and half-past of every hour
}

def scheduleCheck() {
  def response = getWeatherFeature("forecast", zipcode)

  if (isStormy(response)) {
    sensors.each {
      log.debug it?.latestValue
    }

    def open = sensors.findAll { it?.latestValue == 'open' }

    if (open) {
      send("Reported ${response} coming and the following things are open: ${open.join(', ')}")
    }

	else {
      log.warn("Reported ${response} coming but everything looks closed.")
    }
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

  log.debug msg
}

private isStormy(json) {
  def STORMY = ['rain', 'snow', 'showers', 'sprinkles', 'precipitation']

  def forecast = json?.forecast?.txt_forecast?.forecastday?.first()

  if (forecast) {
    def text = forecast?.fcttext?.toLowerCase()

    log.debug(text)

    if (text) {
      def result = false

      for (int i = 0; i < STORMY.size() && !result; i++) {
        if(text.contains(STORMY[i])) {
          result = STORMY[i]
        }
      }

      return result
    }

    else {
      log.warn("Got forecast, couldn't parse")
      return false
    }
  }

  else {
    log.warn("Did not get a forecast: $json")
    return false
  }
}
