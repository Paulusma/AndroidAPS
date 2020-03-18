package info.nightscout.androidaps.data;

import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import info.nightscout.androidaps.R;
import info.nightscout.androidaps.plugins.general.overview.OverviewFragment;
import info.nightscout.androidaps.utils.SP;

/**
 * Created by mike on 12.10.2016.
 */

public class QuickWizard {
    private static Logger log = LoggerFactory.getLogger(QuickWizard.class);

    private JSONArray storage = new JSONArray();

    public void setData(JSONArray newData) {
        try {
            List<JSONObject> myJsonArrayAsList = new ArrayList<JSONObject>();
            for (int i = 0; i < newData.length(); i++)
                myJsonArrayAsList.add(newData.getJSONObject(i));

            Collections.sort(myJsonArrayAsList, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject jsonObjectA, JSONObject jsonObjectB) {
                    int compare = 0;
                    try {
                        String keyA = jsonObjectA.getString("buttonText").toLowerCase();
                        String keyB = jsonObjectB.getString("buttonText").toLowerCase();
                        compare = keyA.compareTo(keyB);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    return compare;
                }
            });
            storage = new JSONArray();
            for (int i = 0; i < myJsonArrayAsList.size(); i++) {
                storage.put(myJsonArrayAsList.get(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void save() {
        setData(storage);
        SP.putString("QuickWizard", storage.toString());
    }

    public int size() {
        return storage.length();
    }

    public QuickWizardEntry get(int position) {
        try {
            return new QuickWizardEntry((JSONObject) storage.get(position), position);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return null;
    }

    public Boolean isActive() {
        for (int i = 0; i < storage.length(); i++) {
            try {
                if (new QuickWizardEntry((JSONObject) storage.get(i), i).isActive()) return true;
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }
        return false;
    }

    public QuickWizardEntry getActive(OverviewFragment fragment, String methodName) {
        QuickWizardEntry selectedEntry = null;
        Map<String, QuickWizardEntry> activeEntries = new HashMap<>();
        QuickWizardEntry defaultEntry = null;
        Integer maxCarbs = 0;
        final String SELECT_MEAL = "_Selecteer een maaltijd_";

        for (int i = 0; i < storage.length(); i++) {
            QuickWizardEntry entry;
            try {
                entry = new QuickWizardEntry((JSONObject) storage.get(i), i);
            } catch (JSONException e) {
                continue;
            }
//            if (entry.isActive()) {
            activeEntries.put(entry.buttonText(), entry);
            if (entry.carbs() > maxCarbs) {
                defaultEntry = entry;
                maxCarbs = entry.carbs();
            }
//            }
        }

        if (!methodName.equals("")) {
            try {
                Class<?> c = fragment.getClass();// Class.forName("class name");
                Class[] parameterTypes = new Class[]{QuickWizardEntry.class};
                Method method = c.getDeclaredMethod(methodName, parameterTypes);
                //               if (activeEntries.size() > 1) {
                // Let user select among active entries
                List<String> names = new ArrayList<>();
                for (String activeEntry : activeEntries.keySet()) {
                    names.add(activeEntry);
                }
                Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                names.add(0, SELECT_MEAL);

                final AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getActivity());
                LayoutInflater inflater = fragment.getLayoutInflater();
                builder.setView(inflater.inflate(R.layout.dialog_select_meal, null));
//                    builder.setTitle("Selecteer een maaltijd");
                final AlertDialog dialog = builder.create();
                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                dialog.show();

                Spinner spinFoodItems = dialog.findViewById(R.id.spinFooditems);

                ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(fragment.getActivity(),
                        R.layout.spinner_item, names);
                spinFoodItems.setAdapter(dataAdapter);
                int pos = dataAdapter.getPosition(SELECT_MEAL);
                spinFoodItems.setSelection(pos);
                spinFoodItems.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        String name = (String) parent.getItemAtPosition(position);
                        QuickWizardEntry selectedActiveEntry = activeEntries.get(name);
                        if (!name.equals(SELECT_MEAL)) {
                            dialog.dismiss();
                            try {
                                method.invoke(fragment, selectedActiveEntry);
                            } catch (Exception e) {
                                Toast.makeText(fragment.getActivity(), "Fout in uitvoeren functie: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    }

                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
//                } else
//                    method.invoke(fragment,defaultEntry);
            } catch (Exception e) {
                Toast.makeText(fragment.getActivity(), "Fout in uitvoeren functie: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else
            selectedEntry = defaultEntry;

        return selectedEntry;
    }

    public QuickWizardEntry newEmptyItem() {
        return new QuickWizardEntry();
    }

    public void addOrUpdate(QuickWizardEntry newItem) {
        if (newItem.position == -1)
            storage.put(newItem.storage);
        else {
            try {
                storage.put(newItem.position, newItem.storage);
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }
        }
        save();
    }

    public void remove(int position) {
        storage.remove(position);
        save();
    }
}
