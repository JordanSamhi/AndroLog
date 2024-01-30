import source_instrumenter

from tkinter import *
from tkinter import filedialog


def clicked():
    file_path = filedialog.askdirectory(initialdir="$HOME", title="Select folder")
    source_instrumenter.run_instrumentation(file_path)
    label2 = Label(window, text="Success", background="green", fg="white")
    label2.grid(row=3, column=2)


window = Tk()
window.title("JacocoInstrumenter")
window.geometry('175x180')
window.grid()
label = Label(window, text="Let's Instrument", font=20)
label.grid(column=2, row=1)
btn = Button(window, text="Select Folder to Instrument", command=clicked)
btn.grid(column=2, row=2)
window.mainloop()
