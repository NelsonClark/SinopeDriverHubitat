/**
 *  Sinope RM3500ZB Water heater controller Driver for Hubitat
 *  Source: https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/RM3500ZB_Sinope_Hubitat.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * v1.0.0 Initial commit
 */

metadata
{
     definition(name: "Water heater controller RM3500ZB", namespace: "sacua", author: "Samuel Cuerrier Auclair") {
        capability "Switch"
        capability "Configuration"
        capability "Refresh"
        capability "Outlet"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "TemperatureMeasurement"
         
        attribute "cost", "number"
        attribute "dailyCost", "number"
        attribute "weeklyCost", "number"
        attribute "monthlyCost", "number"
        attribute "yearlyCost", "number"
        attribute "dailyEnergy", "number"
        attribute "weeklyEnergy", "number"
        attribute "monthlyEnergy", "number"
        attribute "yearlyEnergy", "number"
        attribute "mode", "String"
        
        command "resetEnergyOffset", ["number"]
        command "resetDailyEnergy"
        command "resetWeeklyEnergy"
        command "resetMonthlyEnergy"
        command "resetYearlyEnergy"
        command "autoMode"
        command "manualMode"
      
          
        preferences {
          input name: "Hyst", type: "number", title: "Hysteresis degrees", description: "Hysteresis in degrees C when in auto mode. Below the setpoint-hysteresis the controler switch to on, above the setpoint+hysteresis switch to off", range: "0.1..10", defaultValue: 1
          input name: "minTemp", type: "number", title: "Water Temperature", description: "Water temperature to keep when in auto mode in C (30..60)", range: "30..60", defaultValue: 49
          input name: "tempChange", type: "number", title: "Temperature change", description: "Minumum change of temperature reading to trigger report in Celsius/100, 10..200", range: "10..200", defaultValue: 50
          input name: "PowerReport", type: "number", title: "Power change", description: "Amount of wattage difference to trigger power report (1..*)",  range: "1..*", defaultValue: 30
          input name: "energyChange", type: "number", title: "Energy increment", description: "Minimum increment of the energy meter in Wh to trigger energy reporting (10..*)", range: "10..*", defaultValue: 10
          input name: "energyPrice", type: "float", title: "c/kWh Cost:", description: "Electric Cost per Kwh in cent", range: "0..*", defaultValue: 9.38
          input name: "weeklyReset", type: "enum", title: "Weekly reset day", description: "Day on which the weekly energy meter return to 0", options:["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"], defaultValue: "Sunday", multiple: false, required: true
          input name: "yearlyReset", type: "enum", title: "Yearly reset month", description: "Month on which the yearly energy meter return to 0", options:["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"], defaultValue: "January", multiple: false, required: true
          input name: "txtEnable", type: "bool", title: "Enable logging info", defaultValue: true
        }
        
    }
}

//-- Installation ----------------------------------------------------------------------------------------

def installed() {
    if (txtEnable) log.info "installed() : running configure()"
    if (state.time == null)  
      state.time = now()
    if (state.energyValue == null) 
      state.energyValue = 0 as double
    if (state.costValue == null) 
      state.costValue = 0 as float
    if (state.powerValue == null)  
      state.powerValue = 0 as int
    configure()
}

def updated() {
    if (txtEnable) log.info "updated() : running configure()"
    
    if (state.time == null)  
      state.time = now()
    if (state.energyValue == null) 
      state.energyValue = 0 as double
    if (state.powerValue == null)  
      state.powerValue = 0 as int
    if (state.costValue == null) 
      state.costValue = 0 as float
    
    if (!state.updatedLastRanAt || now() >= state.updatedLastRanAt + 5000) {
      state.updatedLastRanAt = now()
      configure()
      refresh()
   }
}

def uninstalled() {
    if (txtEnable) log.info "uninstalled() : unscheduling configure() and reset()"
    try {    
        unschedule()
    } catch (errMsg) {
        log.info "uninstalled(): Error unschedule() - ${errMsg}"
    }
}

      
//-- Parsing -----------------------------------------------------------------------------------------

// parse events into attributes
def parse(String description) {
    if (txtEnable) log.debug "parse - description = ${description}"
    def result = []
    def cluster = zigbee.parse(description)
    if (description?.startsWith("read attr -")) {
        // log.info description
        def descMap = zigbee.parseDescriptionAsMap(description)
        result += createCustomMap(descMap)
        if(descMap.additionalAttrs){
               def mapAdditionnalAttrs = descMap.additionalAttrs
            mapAdditionnalAttrs.each{add ->
                add.cluster = descMap.cluster
                result += createCustomMap(add)
            }
        }
    }
    return result
}

private createCustomMap(descMap){
    def result = null
    def map = [: ]
        if (descMap.cluster == "0006" && descMap.attrId == "0000") {
            map.name = "switch"
            map.value = getSwitchMap()[descMap.value]
            
        } else if (descMap.cluster == "0B04" && descMap.attrId == "050B") {
            map.name = "power"
            map.value = getActivePower(descMap.value)
            map.unit = "W"
        
        } else if (descMap.cluster == "0702" && descMap.attrId == "0000") {
            state.energyValue = getEnergy(descMap.value) as BigInteger
            runIn(2,energyCalculation)
            
        } else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
		    map.name = "temperature"
		    map.value = getTemperature(descMap.value)
            runIn(2,hyst)
        }
        
    if (map) {
        def isChange = isStateChange(device, map.name, map.value.toString())
        map.displayed = isChange
        result = createEvent(map)
    }
    return result
}
            
//-- Capabilities -----------------------------------------------------------------------------------------

def configure(){    
    if (txtEnable) log.info "configure()"    
    try
    {
        unschedule()
    }
    catch (e)
    {
    }
    
    schedule("0 0 * * * ? *", energySecCalculation)
    schedule("0 0 0 * * ? *", resetDailyEnergy)
    schedule("0 0 0 1 * ? *", resetMonthlyEnergy)
    
    if (weeklyReset == null)
		weeklyReset = "Sunday" as String
    if (yearlyReset == null)
		yearlyReset = "January" as String
    
    if (yearlyReset == "January") {
        schedule("0 0 0 1 1 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 2 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "March") {
        schedule("0 0 0 1 3 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "April") {
        schedule("0 0 0 1 4 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "May") {
        schedule("0 0 0 1 5 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 6 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 7 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 8 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 9 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 10 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 11 ? *", resetYearlyEnergy)
    } else if (yearlyReset == "February") {
        schedule("0 0 0 1 12 ? *", resetYearlyEnergy)
    }
    
    if (weeklyReset == "Sunday") {
        schedule("0 0 0 ? * 1 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Monday") {
        schedule("0 0 0 ? * 2 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Tuesday") {
        schedule("0 0 0 ? * 3 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Wednesday") {
        schedule("0 0 0 ? * 4 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Thursday") {
        schedule("0 0 0 ? * 5 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Friday") {
        schedule("0 0 0 ? * 6 *", resetWeeklyEnergy)
    } else if (weeklyReset == "Saturday") {
        schedule("0 0 0 ? * 7 *", resetWeeklyEnergy)
    }

    // Prepare our zigbee commands
    def cmds = []

    // Configure Reporting
    if (tempChange == null)
		tempChange = 50 as int
    if (PowerReport == null)
		PowerReport = 30 as int
	if (energyChange == null)
		energyChange = 10 as int
            
    cmds += zigbee.configureReporting(0x0402, 0x0000, 0x29, 30, 580, (int) tempChange)  //local temperature
    cmds += zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 600, null)           //On off state
    cmds += zigbee.configureReporting(0x0B04, 0x050B, 0x29, 30, 600, (int) PowerReport)
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 299, 1799, (int) energyChange) //Energy reading
    
    sendZigbeeCommands(cmds) // Submit zigbee commands
    if (device.currentValue("mode") == null)
        sendEvent(name: "mode", value: "manual")
    runIn(1,refresh)
    runIn(2,hyst) //in case the temperature setting change when in auto mode
    
}

def refresh() {
    if (txtEnable) log.info "refresh()"
    
    def cmds = []
    cmds += zigbee.readAttribute(0x0402, 0x0000) //Read Local Temperature
    cmds += zigbee.readAttribute(0x0006, 0x0000) //Read On off state
    cmds += zigbee.readAttribute(0x0B04, 0x050B) //Read thermostat Active power
    cmds += zigbee.readAttribute(0x0702, 0x0000) //Read energy delivered
    
    sendZigbeeCommands(cmds) // Submit zigbee commands
}   


def off() {
    def cmds = []
    cmds += zigbee.command(0x0006, 0x00)
    sendZigbeeCommands(cmds)
}

def on() {
    def cmds = []
    cmds += zigbee.command(0x0006, 0x01)
    sendZigbeeCommands(cmds)
}


def autoMode() {
    sendEvent(name: "mode", value: "auto")
}

def manualMode() {
    sendEvent(name: "mode", value: "manual")
}


def hyst() {
    if (minTemp == null)
        minTemp = 49 as float
    if ((float) minTemp - device.currentValue("temperature") > Float.parseFloat(Hyst) && device.currentValue("mode") == "auto" && device.currentValue("switch") == "off") {
        on()
    } else if (device.currentValue("temperature") - (float) minTemp > Float.parseFloat(Hyst) && device.currentValue("mode") == "auto" && device.currentValue("switch") == "on") {
        off()
    }
}


def resetEnergyOffset(text) {
     if (text != null) {
          BigInteger newOffset = text.toBigInteger()
          state.dailyEnergy = state.dailyEnergy - state.offsetEnergy + newOffset
          state.weeklyEnergy = state.weeklyEnergy - state.offsetEnergy + newOffset
          state.monthlyEnergy = state.monthlyEnergy - state.offsetEnergy + newOffset
          state.yearlyEnergy = state.yearlyEnergy - state.offsetEnergy + newOffset
          state.offsetEnergy = newOffset
          float totalEnergy = (state.energyValue + state.offsetEnergy)/1000
          sendEvent(name: "energy", value: totalEnergy, unit: "kWh")
          runIn(2,energyCalculation)
          runIn(2,energySecCalculation)
     }
}

def energyCalculation() {
    if (state.offsetEnergy == null)
        state.offsetEnergy = 0 as BigInteger
    if (state.dailyEnergy == null)
       state.dailyEnergy = state.energyValue as BigInteger
    if (device.currentValue("energy") == null)
        sendEvent(name: "energy", value: 0, unit: "kWh")
     
    if (state.energyValue + state.offsetEnergy < device.currentValue("energy")*1000) { //Although energy are parse as BigInteger, sometimes (like 1 times per month during heating  time) the value received is lower than the precedent but not zero..., so we define a new offset when that happen
        BigInteger newOffset = device.currentValue("energy")*1000 - state.energyValue as BigInteger
        if (newOffset < 1e10) //Sometimes when the hub boot, the offset is very large... munch too large
            state.offsetEnergy = newOffset
    }
    
    float dailyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.dailyEnergy)/1000)
    float totalEnergy = (state.energyValue + state.offsetEnergy)/1000
    
    localCostPerKwh = energyPrice as float
    float dailyCost = roundTwoPlaces(dailyEnergy*localCostPerKwh/100)
    
    sendEvent(name: "dailyEnergy", value: dailyEnergy, unit: "kWh")
    sendEvent(name: "dailyCost", value: dailyCost, unit: "\$")
    sendEvent(name: "energy", value: totalEnergy, unit: "kWh")
}

def energySecCalculation() { //This one is performed every hour to not overwhelm the number of events which will create a warning in hubitat main page
    if (state.weeklyEnergy == null)
       state.weeklyEnergy = state.energyValue as BigInteger
    if (state.monthlyEnergy == null)
       state.monthlyEnergy = state.energyValue as BigInteger
    if (state.yearlyEnergy == null)
       state.yearlyEnergy = state.energyValue as BigInteger
    
    float weeklyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.weeklyEnergy)/1000)
    float monthlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.monthlyEnergy)/1000)
    float yearlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.yearlyEnergy)/1000)
    float totalEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy)/1000)
    
    localCostPerKwh = energyPrice as float
    float weeklyCost = roundTwoPlaces(weeklyEnergy*localCostPerKwh/100)
    float monthlyCost = roundTwoPlaces(monthlyEnergy*localCostPerKwh/100)
    float yearlyCost = roundTwoPlaces(yearlyEnergy*localCostPerKwh/100)
    float totalCost = roundTwoPlaces(totalEnergy*localCostPerKwh/100)

    sendEvent(name: "weeklyEnergy", value: weeklyEnergy, unit: "kWh")
    sendEvent(name: "monthlyEnergy", value: monthlyEnergy, unit: "kWh")
    sendEvent(name: "yearlyEnergy", value: yearlyEnergy, unit: "kWh")

    sendEvent(name: "weeklyCost", value: weeklyCost, unit: "\$")
    sendEvent(name: "monthlyCost", value: monthlyCost, unit: "\$")
    sendEvent(name: "yearlyCost", value: yearlyCost, unit: "\$")
    sendEvent(name: "cost", value: totalCost, unit: "\$")      
    
}

def resetDailyEnergy() {
	state.dailyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float dailyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.dailyEnergy)/1000)
	float dailyCost = roundTwoPlaces(dailyEnergy*localCostPerKwh/100)
    sendEvent(name: "dailyEnergy", value: dailyEnergy, unit: "kWh")
	sendEvent(name: "dailyCost", value: dailyCost, unit: "\$")
    
}

def resetWeeklyEnergy() {
	state.weeklyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float weeklyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.weeklyEnergy)/1000)
	float weeklyCost = roundTwoPlaces(weeklyEnergy*localCostPerKwh/100)
    sendEvent(name: "weeklyEnergy", value: weeklyEnergy, unit: "kWh")
	sendEvent(name: "weeklyCost", value: weeklyCost, unit: "\$")
}

def resetMonthlyEnergy() {
	state.monthlyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float monthlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.monthlyEnergy)/1000)
	float monthlyCost = roundTwoPlaces(monthlyEnergy*localCostPerKwh/100)
    sendEvent(name: "monthlyEnergy", value: monthlyEnergy, unit: "kWh")
	sendEvent(name: "monthlyCost", value: monthlyCost, unit: "\$")
}

def resetYearlyEnergy() {
	state.yearlyEnergy = state.energyValue + state.offsetEnergy
    if (energyPrice == null)
        energyPrice = 9.38 as float
    localCostPerKwh = energyPrice as float
    float yearlyEnergy = roundTwoPlaces((state.energyValue + state.offsetEnergy - state.yearlyEnergy)/1000)
	float yearlyCost = roundTwoPlaces(yearlyEnergy*localCostPerKwh/100)
    sendEvent(name: "yearlyEnergy", value: yearlyEnergy, unit: "kWh")
	sendEvent(name: "yearlyCost", value: yearlyCost, unit: "\$")
}
                  
//-- Private functions -----------------------------------------------------------------------------------
private void sendZigbeeCommands(cmds) {
    //cmds.removeAll { it.startsWith("delay") }
    def hubAction = new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(hubAction)
}

private getTemperature(value) {
	if (value != null) {
		def celsius = Integer.parseInt(value, 16) / 100
		if (getTemperatureScale() == "C") {
			return celsius
		}
		else {
			return Math.round(celsiusToFahrenheit(celsius))
		}
	}
}

private getSwitchMap() {
  [
    "00": "off",
    "01": "on",
  ]
}

private getActivePower(value) {
  if (value != null)
  {
    def activePower = Integer.parseInt(value, 16)
    return activePower
  }
}


private roundTwoPlaces(val)
{
  return Math.round(val * 100) / 100
}

private hex(value)
{
  String hex = new BigInteger(Math.round(value).toString()).toString(16)
  return hex
}

private getEnergy(value) {
  if (value != null)
  {
    BigInteger EnergySum = new BigInteger(value,16)
    return EnergySum
  }
}

private getTemperatureScale() {
	return "${location.temperatureScale}"
}
