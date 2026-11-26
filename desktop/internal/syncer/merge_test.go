package syncer

import "testing"

func TestParseDevices(t *testing.T) {
	out := "List of devices attached\nR58N12ABC device usb:1-1 product:o1s model:SM_G991B device:o1s transport_id:2\nZY22 unauthorized usb:1-2 transport_id:3\n\n"
	p := ParseDevices(out)
	if len(p) != 2 || p[0].Model != "SM G991B" || p[0].State != "device" || p[1].State != "unauthorized" {
		t.Fatalf("%+v", p)
	}
}
