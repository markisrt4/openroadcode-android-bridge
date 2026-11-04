# Vehicle test lab

In Advanced mode, Automotive → Vehicle Test Lab offers simulated vehicle tests.
The card grid groups baseline, confirmed problems, and reporting/readiness tests.
Select a card to persist the scenario; a checkmark and blue border identify the
selection without relying only on color. TalkBack identifies the cards as checked
or unchecked radio controls. Labels wrap and card heights can grow with text.

“What should I see?” describes the selected simulated problem and the result to
expect in ORC's VEHICLE → HEALTH → Scan Now. It describes expected behavior; it
is not a live verification of ORC's result. DTC identifiers and MIL/record/readiness
values appear only when “Show technical details” is opened. The selection toast
also uses the plain-language scenario name.

Start the simulated vehicle connection before scanning. These controls do not
start the connection, change the physical ECU, or clear codes. “No injected fault”
restores the simulated baseline without stopping the simulator. Pending and
permanent scenarios currently use a misfire record; they are reporting examples,
not additional types of mechanical problem.

The existing stored scenario identifiers and ECU responses remain compatible.
`AutomotiveFaultScenarioTest` checks that each description's expected code,
reporting status, light and readiness values agree with the response helper.
The Build Android APK workflow runs these tests and builds the APK.

Device review: verify the selected card survives leaving/reopening Automotive;
expand technical details, select another card, and check the details update;
check narrow screens, large system text, and TalkBack. Compare the expected result
with an actual ORC scan for baseline, each confirmed fault, pending, permanent,
and unfinished checks. Weather and other subsystem injector controls retain their
existing layout.
