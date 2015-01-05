;This file will be executed next to the application bundle image
;I.e. current directory will contain folder Lighthouse with application files
[Setup]
AppId={{lighthouse}}
AppName=Lighthouse
AppVersion=19
AppVerName=Lighthouse
AppPublisher=Vinumeris
AppComments=Lighthouse
AppCopyright=Copyright (C) 2014
AppPublisherURL=https://www.vinumeris.com
AppSupportURL=https://www.vinumeris.com
;AppUpdatesURL=http://java.com/
DefaultDirName={localappdata}\Lighthouse
DisableStartupPrompt=Yes
DisableDirPage=Yes
DisableProgramGroupPage=Yes
DisableReadyPage=Yes
DisableFinishedPage=Yes
DisableWelcomePage=Yes
DefaultGroupName=Vinumeris
;Optional License
LicenseFile=
;WinXP or above
MinVersion=0,5.1
OutputBaseFilename=Lighthouse
Compression=lzma
SolidCompression=yes
PrivilegesRequired=lowest
SetupIconFile=Lighthouse\Lighthouse.ico
UninstallDisplayIcon={app}\Lighthouse.ico
UninstallDisplayName=Lighthouse
WizardImageStretch=No
WizardSmallImageFile=Lighthouse-setup-icon.bmp
ArchitecturesInstallIn64BitMode=x64
ChangesAssociations=Yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "Lighthouse\Lighthouse.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "Lighthouse\runtime\jre\bin\plugin2\msvcr100.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "Lighthouse\runtime\jre\bin\msvcp100.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "Lighthouse\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Lighthouse"; Filename: "{app}\Lighthouse.exe"; IconFilename: "{app}\Lighthouse.ico"; Check: returnTrue()
Name: "{commondesktop}\Lighthouse"; Filename: "{app}\Lighthouse.exe";  IconFilename: "{app}\Lighthouse.ico"; Check: returnFalse()

[Registry]
Root: HKCU; Subkey: "SOFTWARE\Classes\.lighthouse-project"; ValueType: string; ValueName: ""; ValueData: "lhprojectfile"; Flags: uninsdeletevalue
Root: HKCU; Subkey: "SOFTWARE\Classes\.lighthouse-project\lhprojectfile"; ValueType: string; ValueName: ""; ValueData: ""; Flags: uninsdeletekey
Root: HKCU; Subkey: "SOFTWARE\Classes\.lighthouse-project\lhprojectfile\ShellNew"; ValueType: string; ValueName: ""; ValueData: ""; Flags: uninsdeletekey
Root: HKCU; Subkey: "SOFTWARE\Classes\lhprojectfile"; ValueType: string; ValueName: ""; ValueData: "Lighthouse Project File"; Flags: uninsdeletekey
Root: HKCU; Subkey: "SOFTWARE\Classes\lhprojectfile\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\Lighthouse.ico,0"
Root: HKCU; Subkey: "SOFTWARE\Classes\lhprojectfile\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Lighthouse.exe"" ""%1"""
Root: HKCU; Subkey: "SOFTWARE\Classes\.lighthouse-pledge"; ValueType: string; ValueName: ""; ValueData: "lhpledgefile"; Flags: uninsdeletevalue
Root: HKCU; Subkey: "SOFTWARE\Classes\.lighthouse-pledge\lhpledgefile"; ValueType: string; ValueName: ""; ValueData: ""; Flags: uninsdeletekey
Root: HKCU; Subkey: "SOFTWARE\Classes\.lighthouse-pledge\lhpledgefile\ShellNew"; ValueType: string; ValueName: ""; ValueData: ""; Flags: uninsdeletekey
Root: HKCU; Subkey: "SOFTWARE\Classes\lhpledgefile"; ValueType: string; ValueName: ""; ValueData: "Lighthouse Pledge File"; Flags: uninsdeletekey
Root: HKCU; Subkey: "SOFTWARE\Classes\lhpledgefile\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\Lighthouse.ico,0"
Root: HKCU; Subkey: "SOFTWARE\Classes\lhpledgefile\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Lighthouse.exe"" ""%1"""


[Run]
Filename: "{app}\Lighthouse.exe"; Description: "{cm:LaunchProgram,Lighthouse}"; Flags: nowait postinstall skipifsilent; Check: returnTrue()
Filename: "{app}\Lighthouse.exe"; Parameters: "-install -svcName ""Lighthouse"" -svcDesc ""Lighthouse"" -mainExe ""Lighthouse.exe""  "; Check: returnFalse()

[UninstallRun]
Filename: "{app}\Lighthouse.exe "; Parameters: "-uninstall -svcName Lighthouse -stopOnUninstall"; Check: returnFalse()

[Code]
function returnTrue(): Boolean;
begin
  Result := True;
end;

function returnFalse(): Boolean;
begin
  Result := False;
end;

function InitializeSetup(): Boolean;
begin
// Possible future improvements:
//   if version less or same => just launch app
//   if upgrade => check if same app is running and wait for it to exit
//   Add pack200/unpack200 support?
  Result := True;
end;
