"""Common raw model metrics; reference labels are used only in metrics()."""
import re
from dataset_isolation import normalize
from qwerty_local_contract import parse_prediction
from keyboard_hybrid import kata
from qwerty_input_contract import model_prompt


def input_prompt(observation):
    context=observation.get("context","")
    prompt=("\uee02"+context if context else "")+"\uee00"+kata(observation["native_observed"])+"\uee01"
    return model_prompt(prompt,observation,"raw-state")


def interpret(text, observation, mode, native, verifier):
    if mode=="local":
        result=parse_prediction(text,observation,native)
        if result["reading"]==normalize(observation["native_observed"]):
            result.update(action="K",reading=None)
        return {**result,"surface":None,"text":text}
    if not text or any(c in text for c in "\n\r\uee00\uee01\uee02") or len(text)>128:
        return {"action":"invalid","valid":False,"reading":None,"surface":None,"text":text}
    observed=normalize(observation["native_observed"])
    if normalize(text)==observed or verifier.matches(text,observed):
        action="W" if re.search(r"[a-zA-Z]$",observed) else "K"
        return {"action":action,"valid":True,"reading":None,"surface":None,"text":text}
    return {"action":"E","valid":True,"reading":None,"surface":text,"text":text}


def metrics(rows,predictions,verifier):
    values={k:0 for k in ("repair_total","reading_correct","repair_wrong","repair_missing","keep_total","keep_proposed","wait_total","wait_proposed","preservation_total","preservation_proposed","invalid","action_correct")}
    for row,pred in zip(rows,predictions):
        action=row["expected_action"]
        values[action+"_total"]+=1
        proposed=pred["valid"] and pred["action"]=="E"
        values["invalid"]+=not pred["valid"]
        values["action_correct"]+=pred["action"]=={"keep":"K","wait":"W","repair":"E"}[action]
        if row.get("preservation_canary"):
            values["preservation_total"]+=1; values["preservation_proposed"]+=bool(proposed)
        if action=="repair":
            correct=proposed and (pred["reading"]==normalize(row["target_reading"]) if pred["reading"] else verifier.matches(pred["surface"],normalize(row["target_reading"])))
            values["reading_correct"]+=bool(correct)
            values["repair_wrong"]+=proposed and not correct
            values["repair_missing"]+=not proposed
        elif proposed: values[action+"_proposed"]+=1
    tp=values["reading_correct"]; fp=values["repair_wrong"]+values["keep_proposed"]+values["wait_proposed"]; fn=values["repair_total"]-tp
    values["reading_f0_5"]=1.25*tp/(1.25*tp+fp+.25*fn) if tp+fp+fn else 0.
    return values
