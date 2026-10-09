# PilcrowMD test pack - Old text encoding

**What this is.** A small file saved in an older text encoding (Latin-1, also called ISO-8859-1)
instead of UTF-8, the way some older Windows programs still save text. It belongs to the General
pack. This header uses only plain English letters so that you can read it either way.

**What to look for**

- The file opens, with a notice that it is not in UTF-8.
- The accented letters below show as replacement marks (a question mark in a box or a diamond).
  That is expected: the letters are stored in a form the app does not decode.
- Press **Save**: the app offers to save a copy as a new file and does not overwrite this one.
  This file must stay exactly as it was.

**Known gaps in version 1.0.13**

- The copy you save still shows a replacement mark wherever this file had a letter the app could
  not read.

---

## Accented letters (stored as Latin-1)

- French and others: é è ê ë à â ç ù û ü ô ö ï î
- German: ä ö ü ß Ä Ö Ü
- Spanish: ñ á í ó ú ¡ ¿
- Nordic: å ø æ Å Ø Æ
- Symbols: £ ¥ © ® ° ± µ ½ ¼ ¾ × ÷

## A sentence

À la plage, Zoë a bu un café crème; señor Muñoz a payé 12 £ pour la piñata.

---

**End of the old-encoding file.** This file is public domain (CC0 1.0). Copy, change and share it
freely.
