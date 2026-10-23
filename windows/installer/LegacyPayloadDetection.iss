[Code]
// Absence is used only to skip migration. Never execute an uninstaller or
// remove files/registry entries on the strength of this check.
const
  LegacyInnoCorePayloadNames = '|azookey.dll|azookey32.dll|azookey_windows.dll|frontend.exe|azookey-updater-helper.exe|launcher.exe|azookey-server.exe|azookey-server.dll|ui.exe|';

function LegacyPayloadGetLastError(): LongWord;
  external 'GetLastError@kernel32.dll stdcall';

function PreviousInnoPayloadIsAbsent(InstallLocation: String): Boolean;
var
  RootRec: TFindRec;
  ChildRec: TFindRec;
begin
  Result := False;
  // Missing/inaccessible roots and reparse points remain on the existing
  // fail-closed migration path. Do not follow user-controlled links.
  if not FindFirst(RemoveBackslashUnlessRoot(InstallLocation), RootRec) then
    Exit;
  FindClose(RootRec);
  if ((RootRec.Attributes and FILE_ATTRIBUTE_DIRECTORY) = 0) or
     ((RootRec.Attributes and FILE_ATTRIBUTE_REPARSE_POINT) <> 0) then
    Exit;

  if not FindFirst(AddBackslash(InstallLocation) + '*', ChildRec) then
    Exit;
  repeat
    // Check directory entries rather than FileExists: a core name which is
    // a directory or inaccessible file must not be mistaken for absence.
    if Pos('|' + Lowercase(ChildRec.Name) + '|', LegacyInnoCorePayloadNames) > 0 then
    begin
      FindClose(ChildRec);
      Exit;
    end;
  until not FindNext(ChildRec);
  // ERROR_NO_MORE_FILES, rather than an interrupted/inaccessible enumeration.
  Result := LegacyPayloadGetLastError() = 18;
  FindClose(ChildRec);
end;
