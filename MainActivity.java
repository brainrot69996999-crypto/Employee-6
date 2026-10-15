package com.aiemployees.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override public void onCreate(Bundle b) {
    registerPlugin(EmployeePlugin.class);
    super.onCreate(b);
  }
}
